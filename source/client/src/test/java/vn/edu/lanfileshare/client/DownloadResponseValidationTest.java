package vn.edu.lanfileshare.client;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.client.network.TcpClient;
import vn.edu.lanfileshare.client.transfer.*;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.*;

/** Real adversarial TCP peers supplement, rather than replace, full server upload/download tests. */
class DownloadResponseValidationTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void warmEntropy() {
        UUID.randomUUID();
    }

    private void scenario(String fault) throws Exception {
        UUID fileId = UUID.randomUUID();
        Path target = directory.resolve("user-choice.bin");
        byte[] expected = { 97, 98, 99, 100, 101, 102 };
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0); TcpClient client = new TcpClient(1500)) {
            Future<?> peer = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    DownloadInit init = ControlCodec.decode(FrameCodec.read(socket.getInputStream()),
                            DownloadInit.class);
                    assertEquals(fileId, init.fileId());
                    UUID transferId = UUID.randomUUID();
                    TransferReady ready = new TransferReady(1, init.requestId(), transferId,
                            fault.equals("direction") ? Direction.UPLOAD : Direction.DOWNLOAD,
                            fault.equals("fileId") ? UUID.randomUUID() : fileId,
                            fault.equals("traversal") ? "../escape" : "server-controlled-name.txt", expected.length,
                            sha, fault.equals("readyOffset") ? 1 : 0);
                    FrameCodec.write(socket.getOutputStream(), ControlCodec.encode(MessageType.TRANSFER_READY, ready));
                    if (fault.equals("traversal")) {
                        assertEquals(-1, socket.getInputStream().read());
                        return null;
                    }
                    if (Set.of("direction", "fileId", "readyOffset").contains(fault)) {
                        ErrorMessage error = ControlCodec.decode(FrameCodec.read(socket.getInputStream()),
                                ErrorMessage.class);
                        assertEquals(ErrorCode.BAD_REQUEST, error.code());
                        return null;
                    }
                    ProgressAck first = ControlCodec.decode(FrameCodec.read(socket.getInputStream()),
                            ProgressAck.class);
                    assertEquals(0, first.nextOffset());
                    assertEquals(transferId, first.transferId());
                    assertEquals(init.requestId(), first.requestId());
                    if (fault.equals("timeout")) {
                        assertEquals(MessageType.ERROR, FrameCodec.read(socket.getInputStream()).type());
                        return null;
                    }
                    if (fault.equals("earlyComplete")) {
                        FrameCodec.write(socket.getOutputStream(), ControlCodec.encode(MessageType.TRANSFER_COMPLETE,
                                new TransferComplete(1, init.requestId(), transferId, fileId, expected.length, sha)));
                        assertEquals(MessageType.ERROR, FrameCodec.read(socket.getInputStream()).type());
                        return null;
                    }
                    UUID chunkId = fault.equals("chunkId") ? UUID.randomUUID() : transferId;
                    long offset = fault.equals("offset") ? 1 : 0;
                    byte[] encoded = FileChunkCodec.encode(new FileChunk(chunkId, offset, Arrays.copyOf(expected, 3)));
                    if (fault.equals("length"))
                        ByteBuffer.wrap(encoded).putInt(24, 4);
                    FrameCodec.write(socket.getOutputStream(), new Frame(MessageType.FILE_CHUNK, encoded));
                    if (Set.of("offset", "chunkId", "length").contains(fault)) {
                        ErrorMessage error = ControlCodec.decode(FrameCodec.read(socket.getInputStream()),
                                ErrorMessage.class);
                        assertEquals(fault.equals("offset") ? ErrorCode.OFFSET_MISMATCH
                                : fault.equals("chunkId") ? ErrorCode.TRANSFER_NOT_FOUND : ErrorCode.INVALID_FRAME,
                                error.code());
                        return null;
                    }
                    ProgressAck middle = ControlCodec.decode(FrameCodec.read(socket.getInputStream()),
                            ProgressAck.class);
                    assertEquals(3, middle.nextOffset());
                    // Checkpoint must already describe committed bytes when ACK arrives.
                    try (var entries = Files.list(directory)) {
                        Path metadata = entries.filter(p -> p.toString().endsWith(".part.json")).findFirst()
                                .orElseThrow();
                        assertEquals(3, new ObjectMapper().readTree(Files.readAllBytes(metadata)).path("nextOffset")
                                .longValue());
                    }
                    if (fault.equals("disconnect"))
                        return null;
                    if (fault.equals("duplicate")) {
                        FrameCodec.write(socket.getOutputStream(), new Frame(MessageType.FILE_CHUNK,
                                FileChunkCodec.encode(new FileChunk(transferId, 0, new byte[] { 9, 9, 9 }))));
                        assertEquals(3, ControlCodec.decode(FrameCodec.read(socket.getInputStream()), ProgressAck.class)
                                .nextOffset());
                    }
                    byte[] remainder = fault.equals("hash") ? new byte[] { 9, 9, 9 }
                            : Arrays.copyOfRange(expected, 3, 6);
                    FrameCodec.write(socket.getOutputStream(), new Frame(MessageType.FILE_CHUNK,
                            FileChunkCodec.encode(new FileChunk(transferId, 3, remainder))));
                    assertEquals(6, ControlCodec.decode(FrameCodec.read(socket.getInputStream()), ProgressAck.class)
                            .nextOffset());
                    Frame finish = FrameCodec.read(socket.getInputStream());
                    if (fault.equals("hash"))
                        assertEquals(ErrorCode.HASH_MISMATCH, ControlCodec.decode(finish, ErrorMessage.class).code());
                    else {
                        assertEquals(MessageType.TRANSFER_COMPLETE, finish.type());
                        TransferComplete complete = ControlCodec.decode(finish, TransferComplete.class);
                        assertEquals(init.requestId(), complete.requestId());
                        assertEquals(transferId, complete.transferId());
                        assertEquals(sha, complete.sha256());
                    }
                    return null;
                }
            });
            client.connect("127.0.0.1", listener.getLocalPort());
            List<DownloadProgress> progress = new ArrayList<>();
            if (fault.equals("duplicate")) {
                DownloadResult result = new DownloadService(client).downloadFile(fileId, target, progress::add);
                assertArrayEquals(expected, Files.readAllBytes(result.destination()));
                assertEquals(TransferStatus.COMPLETED, progress.getLast().status());
                assertFalse(Files.exists(directory.resolve("server-controlled-name.txt")));
            } else {
                assertThrows(IOException.class,
                        () -> new DownloadService(client).downloadFile(fileId, target, progress::add));
                assertFalse(Files.exists(target));
                assertFalse(Files.exists(directory.getParent().resolve("escape")));
                assertTrue(progress.stream()
                        .noneMatch(p -> p.status() == TransferStatus.COMPLETED || p.percentage() == 100));
                assertFalse(client.isConnected());
                if (fault.equals("disconnect") || fault.equals("timeout")) {
                    assertEquals(TransferStatus.INTERRUPTED, progress.getLast().status());
                    try (var entries = Files.list(directory)) {
                        Path metadata = entries.filter(p -> p.toString().endsWith(".part.json")).findFirst()
                                .orElseThrow();
                        var json = new ObjectMapper().readTree(Files.readAllBytes(metadata));
                        assertEquals("INTERRUPTED", json.path("status").asText());
                        assertEquals(fault.equals("disconnect") ? 3 : 0, json.path("nextOffset").longValue());
                    }
                } else
                    try (var entries = Files.list(directory)) {
                        assertEquals(0, entries.count());
                    }
            }
            peer.get(8, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void wrongDirection() throws Exception {
        scenario("direction");
    }

    @Test
    void wrongFileId() throws Exception {
        scenario("fileId");
    }

    @Test
    void nonZeroInitialOffset() throws Exception {
        scenario("readyOffset");
    }

    @Test
    void serverNameTraversalCannotCreatePaths() throws Exception {
        scenario("traversal");
    }

    @Test
    void wrongChunkOffset() throws Exception {
        scenario("offset");
    }

    @Test
    void wrongChunkId() throws Exception {
        scenario("chunkId");
    }

    @Test
    void wrongChunkDataLength() throws Exception {
        scenario("length");
    }

    @Test
    void prematureComplete() throws Exception {
        scenario("earlyComplete");
    }

    @Test
    void serverDisconnectKeepsOnlyUnpublishedPartialData() throws Exception {
        scenario("disconnect");
    }

    @Test
    void timeoutHasFiniteDeadline() throws Exception {
        scenario("timeout");
    }

    @Test
    void wrongHashIsReportedAndNeverPublished() throws Exception {
        scenario("hash");
    }

    @Test
    void duplicateChunkIsAckedWithoutDuplicatingBytes() throws Exception {
        scenario("duplicate");
    }
}
