package vn.edu.lanfileshare.client;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.client.network.TcpClient;
import vn.edu.lanfileshare.client.transfer.*;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.*;

/** Adversarial real TCP peers test validation; normal uploads use the actual UploadHandler. */
class UploadResponseValidationTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void warmEntropy() {
        UUID.randomUUID();
    }

    private void reject(String fault) throws Exception {
        Path file = Files.writeString(directory.resolve("input"), "abc");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0); TcpClient client = new TcpClient(1500)) {
            Future<?> peer = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(3000);
                    UploadInit init = ControlCodec.decode(FrameCodec.read(socket.getInputStream()), UploadInit.class);
                    UUID transfer = UUID.randomUUID(), id = UUID.randomUUID();
                    if (fault.equals("timeout")) {
                        assertEquals(-1, socket.getInputStream().read());
                        return;
                    }
                    if (fault.equals("eof"))
                        return;
                    TransferReady ready = new TransferReady(1, init.requestId(), transfer,
                            fault.equals("ready") ? Direction.DOWNLOAD : Direction.UPLOAD, id, init.name(), init.size(),
                            init.sha256(), 0);
                    FrameCodec.write(socket.getOutputStream(), ControlCodec.encode(MessageType.TRANSFER_READY, ready));
                    if (fault.equals("ready")) {
                        assertEquals(-1, socket.getInputStream().read());
                        return;
                    }
                    Frame chunk = FrameCodec.read(socket.getInputStream());
                    assertEquals(MessageType.FILE_CHUNK, chunk.type());
                    ProgressAck ack = new ProgressAck(1,
                            fault.equals("requestId") ? UUID.randomUUID() : init.requestId(),
                            fault.equals("ackId") ? UUID.randomUUID() : transfer, fault.equals("ackOffset") ? 2 : 3);
                    FrameCodec.write(socket.getOutputStream(), ControlCodec.encode(MessageType.PROGRESS_ACK, ack));
                    if (fault.equals("complete"))
                        FrameCodec.write(socket.getOutputStream(),
                                ControlCodec.encode(MessageType.TRANSFER_COMPLETE, new TransferComplete(1,
                                        init.requestId(), transfer, UUID.randomUUID(), 3, init.sha256())));
                    assertEquals(-1, socket.getInputStream().read());
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
            client.connect("127.0.0.1", listener.getLocalPort());
            List<UploadProgress> progress = new ArrayList<>();
            assertThrows(IOException.class, () -> new UploadService(client).uploadFile(file, progress::add));
            assertFalse(client.isConnected());
            assertTrue(progress.stream().noneMatch(p -> p.percentage() == 100));
            peer.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsWrongReadyDirection() throws Exception {
        reject("ready");
    }

    @Test
    void rejectsWrongAckTransferId() throws Exception {
        reject("ackId");
    }

    @Test
    void rejectsWrongAckOffset() throws Exception {
        reject("ackOffset");
    }

    @Test
    void rejectsWrongResponseRequestId() throws Exception {
        reject("requestId");
    }

    @Test
    void rejectsWrongCompletionMetadata() throws Exception {
        reject("complete");
    }

    @Test
    void timeoutDoesNotHangOrReportSuccess() throws Exception {
        reject("timeout");
    }

    @Test
    void disconnectedPeerDoesNotReportSuccess() throws Exception {
        reject("eof");
    }
}
