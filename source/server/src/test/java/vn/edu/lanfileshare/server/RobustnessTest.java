package vn.edu.lanfileshare.server;

import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.server.network.*;
import vn.edu.lanfileshare.common.protocol.*;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.error.ErrorCode;

class RobustnessTest {
    @Test
    void unexpectedServiceFailureIsSanitizedAndAcceptLoopSurvives() throws Exception {
        UUID.randomUUID();
        try (var server = new TcpServer(InetAddress.getLoopbackAddress(), 0, 4, 3000,
                new RequestDispatcher(List::of, (s, f) -> {
                    throw new IllegalStateException("D:/secret/storage/file");
                }))) {
            server.start();
            try (Socket bad = new Socket("127.0.0.1", server.port())) {
                bad.setSoTimeout(3000);
                UUID id = UUID.randomUUID();
                FrameCodec.write(bad.getOutputStream(),
                        ControlCodec.encode(MessageType.UPLOAD_INIT, new UploadInit(1, id, "x", 0, "a".repeat(64))));
                var reply = ControlCodec.decode(FrameCodec.read(bad.getInputStream()), ErrorMessage.class);
                assertEquals(id, reply.requestId());
                assertEquals(ErrorCode.INTERNAL_ERROR, reply.code());
                assertFalse(reply.message().contains("secret"));
            }
            try (Socket good = new Socket("127.0.0.1", server.port())) {
                good.setSoTimeout(3000);
                UUID id = UUID.randomUUID();
                FrameCodec.write(good.getOutputStream(),
                        ControlCodec.encode(MessageType.LIST_FILES, new ListFiles(1, id)));
                assertEquals(MessageType.FILE_LIST, FrameCodec.read(good.getInputStream()).type());
            }
        }
    }

    @Test
    void slowDripFrameHasAbsoluteDeadline() throws Exception {
        UUID.randomUUID();
        ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor();
        try (var server = new TcpServer(InetAddress.getLoopbackAddress(), 0, 2, 300,
                new RequestDispatcher(List::of, (s, f) -> {
                }))) {
            server.start();
            try (Socket socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(2500);
                new DataOutputStream(socket.getOutputStream()).writeInt(1000);
                var drip = writer.scheduleAtFixedRate(() -> {
                    try {
                        socket.getOutputStream().write(1);
                    } catch (IOException ignored) {
                        /* peer deadline closed socket */}
                }, 0, 50, TimeUnit.MILLISECONDS);
                try {
                    long started = System.nanoTime();
                    try {
                        assertEquals(-1, socket.getInputStream().read());
                    } catch (SocketException reset) {
                        // Windows may reset a socket closed with unread inbound bytes.
                        // A read timeout is not a SocketException and must still fail.
                        assertFalse(socket.isClosed());
                    }
                    assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
                } finally {
                    drip.cancel(false);
                }
            }
        } finally {
            writer.shutdownNow();
            assertTrue(writer.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
