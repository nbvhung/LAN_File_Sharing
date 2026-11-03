package vn.edu.lanfileshare.server;

import java.io.*;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.protocol.*;
import vn.edu.lanfileshare.server.network.*;

class ProtocolSessionTest {
    @org.junit.jupiter.api.BeforeAll static void initializeEntropyBeforeSocketDeadlines() {
        java.util.UUID.randomUUID();
    }

    private TcpServer server(int timeout) throws IOException {
        return new TcpServer(InetAddress.getLoopbackAddress(), 0, 8, timeout,
            new RequestDispatcher(List::of, (session, frame) -> RequestDispatcher.error(session,
                ControlCodec.requestId(frame), ErrorCode.BAD_REQUEST, "Unsupported in Phase 1")));
    }
    private Socket socket(TcpServer server) throws IOException {
        Socket s = new Socket("127.0.0.1", server.port()); s.setSoTimeout(3000); return s;
    }
    @Test void wrongVersionRepliesWithSameIdThenCloses() throws Exception {
        try (TcpServer server = server(2000)) {
            server.start();
            try (Socket socket = socket(server)) {
                UUID id = UUID.randomUUID();
                FrameCodec.write(socket.getOutputStream(), ControlCodec.encode(MessageType.LIST_FILES, new ListFiles(2, id)));
                ErrorMessage error = ControlCodec.decode(FrameCodec.read(socket.getInputStream()), ErrorMessage.class);
                assertEquals(id, error.requestId()); assertEquals(ErrorCode.UNSUPPORTED_VERSION, error.code());
                assertEquals(-1, socket.getInputStream().read());
            }
        }
    }
    @Test void invalidJsonFieldAndUnsupportedRequestAreControlledAndKeepSession() throws Exception {
        try (TcpServer server = server(2000)) {
            server.start();
            try (Socket socket = socket(server)) {
                UUID id = UUID.randomUUID();
                String invalid = "{\"protocolVersion\":\"1\",\"requestId\":\"" + id + "\"}";
                FrameCodec.write(socket.getOutputStream(), new Frame(MessageType.LIST_FILES, invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                ErrorMessage bad = ControlCodec.decode(FrameCodec.read(socket.getInputStream()), ErrorMessage.class);
                assertEquals(ErrorCode.BAD_REQUEST, bad.code()); assertEquals(id, bad.requestId());
                FrameCodec.write(socket.getOutputStream(), ControlCodec.encode(MessageType.DOWNLOAD_INIT, new DownloadInit(1, id, UUID.randomUUID())));
                ErrorMessage unsupported = ControlCodec.decode(FrameCodec.read(socket.getInputStream()), ErrorMessage.class);
                assertEquals(id, unsupported.requestId()); assertEquals(ErrorCode.BAD_REQUEST, unsupported.code());
                FrameCodec.write(socket.getOutputStream(), ControlCodec.encode(MessageType.LIST_FILES, new ListFiles(1, id)));
                assertTrue(ControlCodec.decode(FrameCodec.read(socket.getInputStream()), FileList.class).files().isEmpty());
            }
        }
    }
    @Test void idleTimeoutAndInvalidBinaryCloseSocket() throws Exception {
        try (TcpServer server = server(200)) {
            server.start();
            try (Socket idle = socket(server)) { assertEquals(-1, idle.getInputStream().read()); }
            try (Socket bad = socket(server)) {
                FrameCodec.write(bad.getOutputStream(), new Frame(MessageType.FILE_CHUNK, new byte[29]));
                assertEquals(-1, bad.getInputStream().read());
            }
        }
    }
}
