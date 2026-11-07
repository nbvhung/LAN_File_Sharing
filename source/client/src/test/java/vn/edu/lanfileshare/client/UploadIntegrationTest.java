package vn.edu.lanfileshare.client;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.client.network.*;
import vn.edu.lanfileshare.client.transfer.*;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.io.FileHashes;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.*;
import vn.edu.lanfileshare.server.network.*;
import vn.edu.lanfileshare.server.storage.*;
import vn.edu.lanfileshare.server.transfer.*;

class UploadIntegrationTest {
    @TempDir
    Path temporary;
    private FileStorageService storage;
    private JsonFileCatalog catalog;

    @BeforeAll
    static void warmEntropy() {
        UUID.randomUUID();
    }

    @BeforeEach
    void setup() throws Exception {
        storage = new FileStorageService(new StorageConfiguration(temporary.resolve("runtime")));
        catalog = new JsonFileCatalog(storage);
    }

    record Running(TcpServer server, UploadHandler uploads) implements AutoCloseable {
        public void close() throws IOException {
            try {
                server.close();
            } finally {
                uploads.close();
            }
        }

        TcpClient client() throws IOException {
            TcpClient c = new TcpClient(5000);
            c.connect("127.0.0.1", server.port());
            return c;
        }
    }

    private Running start() throws Exception {
        return start(UploadHandler.DEFAULT_MAX_FILE_BYTES);
    }

    private Running start(long limit) throws Exception {
        UploadHandler uploads = new UploadHandler(storage, catalog, limit);
        TcpServer server = new TcpServer(InetAddress.getLoopbackAddress(), 0, 8, 5000,
                new RequestDispatcher(catalog, uploads));
        server.start();
        return new Running(server, uploads);
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    final class Peer implements AutoCloseable {
        final Socket socket;
        UUID requestId;
        TransferReady ready;

        Peer(Running running) throws IOException {
            socket = new Socket("127.0.0.1", running.server().port());
            socket.setSoTimeout(5000);
        }

        Frame receive() throws IOException {
            return FrameCodec.read(socket.getInputStream());
        }

        void send(Frame frame) throws IOException {
            FrameCodec.write(socket.getOutputStream(), frame);
        }

        void begin(String name, long size, String sha) throws Exception {
            requestId = UUID.randomUUID();
            send(ControlCodec.encode(MessageType.UPLOAD_INIT, new UploadInit(1, requestId, name, size, sha)));
            Frame frame = receive();
            assertEquals(MessageType.TRANSFER_READY, frame.type());
            ready = ControlCodec.decode(frame, TransferReady.class);
            assertEquals(requestId, ready.requestId());
            assertEquals(0, ready.nextOffset());
            assertEquals(Direction.UPLOAD, ready.direction());
        }

        void chunk(long offset, byte[] bytes) throws IOException {
            send(new Frame(MessageType.FILE_CHUNK,
                    FileChunkCodec.encode(new FileChunk(ready.transferId(), offset, bytes))));
        }

        void ack(long offset) throws Exception {
            Frame frame = receive();
            assertEquals(MessageType.PROGRESS_ACK, frame.type());
            ProgressAck ack = ControlCodec.decode(frame, ProgressAck.class);
            assertEquals(requestId, ack.requestId());
            assertEquals(ready.transferId(), ack.transferId());
            assertEquals(offset, ack.nextOffset());
            TransferSnapshot persisted = new TransferStateStore(storage.configuration()).read(ready.transferId());
            assertEquals(offset, persisted.nextOffset());
        }

        void error(ErrorCode code) throws Exception {
            Frame frame = receive();
            assertEquals(MessageType.ERROR, frame.type());
            ErrorMessage error = ControlCodec.decode(frame, ErrorMessage.class);
            assertEquals(requestId, error.requestId());
            assertEquals(code, error.code());
            assertFalse(error.message().contains(temporary.toString()));
        }

        public void close() throws IOException {
            socket.close();
        }
    }

    private void assertSaved(Path source, TransferResult result) throws Exception {
        Path saved = storage.completedPath(result.file().fileId());
        assertEquals(-1, Files.mismatch(source, saved));
        assertEquals(FileHashes.sha256(source), FileHashes.sha256(saved));
        assertEquals(Files.size(source), result.file().size());
        assertEquals(result.file(), catalog.findById(result.file().fileId()).orElseThrow());
        assertFalse(Files.exists(storage.partPath(result.transferId())));
        assertEquals(TransferState.COMPLETED,
                new TransferStateStore(storage.configuration()).read(result.transferId()).state());
    }

    private void successful(Path source) throws Exception {
        try (Running running = start(); TcpClient client = running.client()) {
            List<UploadProgress> progress = new ArrayList<>();
            TransferResult result = new UploadService(client).uploadFile(source, progress::add);
            assertSaved(source, result);
            assertEquals(source.getFileName().toString(), result.file().name());
            assertEquals(0, progress.getFirst().percentage());
            assertEquals(100, progress.getLast().percentage());
            assertEquals(Files.size(source), progress.getLast().bytesTransferred());
            long previous = 0;
            for (UploadProgress event : progress) {
                assertTrue(event.bytesTransferred() >= previous);
                previous = event.bytesTransferred();
                assertEquals(Files.size(source), event.totalBytes());
                assertTrue(event.bytesPerSecond() >= 0);
            }
            assertEquals(List.of(result.file()), client.listFiles().files()); // Same connection after COMPLETE.
        }
    }

    @Test
    void emptyFileHasReadyThenCompleteWithoutChunks() throws Exception {
        successful(Files.createFile(temporary.resolve("empty.bin")));
    }

    @Test
    void textFileMatchesKnownSha256() throws Exception {
        Path source = Files.writeString(temporary.resolve("text.txt"), "abc");
        successful(source);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                catalog.listFiles().getFirst().sha256());
    }

    @Test
    void unicodeNameIsPreservedOnWireAndInCatalog() throws Exception {
        successful(Files.writeString(temporary.resolve("t\u1ec7p-\u0111\u1ecdc.txt"), "unicode"));
    }

    private Path binary(int size) throws IOException {
        Path source = temporary.resolve("binary-" + size + ".bin");
        Random random = new Random(1234);
        try (OutputStream output = Files.newOutputStream(source)) {
            byte[] block = new byte[65536];
            for (int remaining = size; remaining > 0; remaining -= Math.min(remaining, block.length)) {
                random.nextBytes(block);
                block[0] = 0;
                output.write(block, 0, Math.min(remaining, block.length));
            }
        }
        return source;
    }

    @Test
    void binaryOneMiBIncludingNullBytes() throws Exception {
        successful(binary(1024 * 1024));
    }

    @Test
    void binaryTenMiBStreamsWithoutWholeFileBuffer() throws Exception {
        successful(binary(10 * 1024 * 1024));
    }

    @Test
    void duplicateNamesProduceIndependentUuidFiles() throws Exception {
        Files.createDirectories(temporary.resolve("a"));
        Files.createDirectories(temporary.resolve("b"));
        Path first = Files.writeString(temporary.resolve("a/same.txt"), "first");
        Path second = Files.writeString(temporary.resolve("b/same.txt"), "second");
        try (Running running = start(); TcpClient client = running.client()) {
            UploadService service = new UploadService(client);
            TransferResult one = service.uploadFile(first, p -> {
            }), two = service.uploadFile(second, p -> {
            });
            assertNotEquals(one.file().fileId(), two.file().fileId());
            assertSaved(first, one);
            assertSaved(second, two);
            assertEquals(2, client.listFiles().files().size());
        }
    }

    @Test
    void missingDirectoryOversizedAndUnsafeClientSourceFailBeforeInit() throws Exception {
        try (Running running = start(); TcpClient client = running.client()) {
            UploadService service = new UploadService(client, 2);
            assertThrows(IOException.class, () -> service.uploadFile(temporary.resolve("missing"), p -> fail()));
            assertThrows(IOException.class, () -> service.uploadFile(temporary, p -> fail()));
            Path tooBig = Files.writeString(temporary.resolve("big"), "abc");
            assertThrows(IOException.class, () -> service.uploadFile(tooBig, p -> fail()));
            Path unsafe = Files.writeString(temporary.resolve("..unsafe"), "x");
            assertThrows(IOException.class, () -> service.uploadFile(unsafe, p -> fail()));
            assertTrue(client.listFiles().files().isEmpty());
            try (var entries = Files.list(storage.configuration().staging())) {
                assertEquals(0, entries.count());
            }
            assertEquals(1L << 30, UploadService.DEFAULT_MAX_FILE_BYTES);
        }
    }

    @Test
    void serverEnforcesItsOwnFileLimit() throws Exception {
        try (Running running = start(2); Peer peer = new Peer(running)) {
            peer.requestId = UUID.randomUUID();
            peer.send(ControlCodec.encode(MessageType.UPLOAD_INIT,
                    new UploadInit(1, peer.requestId, "big", 3, hash(new byte[3]))));
            peer.error(ErrorCode.FILE_TOO_LARGE);
            assertTrue(catalog.listFiles().isEmpty());
        }
    }

    @Test
    void serverRejectsTraversalBackslashAndControlCharacters() throws Exception {
        try (Running running = start(); Peer peer = new Peer(running)) {
            for (String name : new String[] { "../escape", "a\\b", "..", "bad\nname", "a/b" }) {
                peer.requestId = UUID.randomUUID();
                peer.send(ControlCodec.encode(MessageType.UPLOAD_INIT,
                        new UploadInit(1, peer.requestId, name, 0, hash(new byte[0]))));
                peer.error(ErrorCode.INVALID_NAME);
            }
            assertTrue(catalog.listFiles().isEmpty());
        }
    }

    @Test
    void duplicateGapOverlapAndOversizeChunkDoNotCorruptBytes() throws Exception {
        byte[] bytes = { 0, 1, 2, 3, 4, 5 };
        try (Running running = start(); Peer peer = new Peer(running)) {
            peer.begin("binary", bytes.length, hash(bytes));
            peer.chunk(1, new byte[] { 1 });
            peer.error(ErrorCode.OFFSET_MISMATCH);
            peer.chunk(0, Arrays.copyOf(bytes, 3));
            peer.ack(3);
            assertTrue(catalog.listFiles().isEmpty());
            assertFalse(Files.exists(storage.completedPath(peer.ready.fileId())));
            peer.chunk(0, new byte[] { 9, 9, 9 });
            peer.ack(3); // Duplicate is ignored, including its bytes.
            assertEquals(3, Files.size(storage.partPath(peer.ready.transferId())));
            peer.chunk(2, new byte[] { 2, 3 });
            peer.error(ErrorCode.OFFSET_MISMATCH);
            peer.chunk(3, new byte[] { 3, 4, 5, 6 });
            peer.error(ErrorCode.OFFSET_MISMATCH);
            peer.chunk(3, Arrays.copyOfRange(bytes, 3, 6));
            peer.ack(6);
            TransferComplete complete = ControlCodec.decode(peer.receive(), TransferComplete.class);
            assertEquals(peer.requestId, complete.requestId());
            assertArrayEquals(bytes, Files.readAllBytes(storage.completedPath(peer.ready.fileId())));
            assertEquals(hash(bytes), catalog.listFiles().getFirst().sha256());
        }
    }

    @Test
    void transferIdCannotBeUsedFromAnotherSocket() throws Exception {
        try (Running running = start(); Peer owner = new Peer(running); Peer other = new Peer(running)) {
            owner.begin("one", 1, hash(new byte[] { 1 }));
            other.begin("two", 1, hash(new byte[] { 2 }));
            other.send(new Frame(MessageType.FILE_CHUNK,
                    FileChunkCodec.encode(new FileChunk(owner.ready.transferId(), 0, new byte[] { 1 }))));
            other.error(ErrorCode.TRANSFER_NOT_FOUND);
            owner.chunk(0, new byte[] { 1 });
            owner.ack(1);
            assertEquals(MessageType.TRANSFER_COMPLETE, owner.receive().type());
            assertEquals(1, catalog.listFiles().size());
        }
    }

    private TransferSnapshot awaitState(UUID transferId, TransferState expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        TransferSnapshot snapshot;
        do {
            snapshot = new TransferStateStore(storage.configuration()).read(transferId);
            if (snapshot.state() == expected)
                return snapshot;
            Thread.sleep(10); // Test condition polling, not upload progress simulation.
        } while (System.nanoTime() < deadline);
        fail("Expected " + expected + " but found " + snapshot.state());
        return snapshot;
    }

    @Test
    void invalidChunkLengthClosesSocketWithoutPublishing() throws Exception {
        try (Running running = start(); Peer peer = new Peer(running)) {
            peer.begin("broken", 3, hash(new byte[3]));
            byte[] payload = FileChunkCodec.encode(new FileChunk(peer.ready.transferId(), 0, new byte[3]));
            ByteBuffer.wrap(payload).putInt(24, 4);
            peer.send(new Frame(MessageType.FILE_CHUNK, payload));
            assertEquals(-1, peer.socket.getInputStream().read());
            awaitState(peer.ready.transferId(), TransferState.INTERRUPTED);
            assertTrue(catalog.listFiles().isEmpty());
        }
    }

    @Test
    void wrongHashFailsAndRemovesCorruptPart() throws Exception {
        try (Running running = start(); Peer peer = new Peer(running)) {
            peer.begin("corrupt", 3, hash(new byte[] { 1, 2, 3 }));
            peer.chunk(0, new byte[] { 9, 9, 9 });
            peer.ack(3);
            peer.error(ErrorCode.HASH_MISMATCH);
            assertEquals(TransferState.FAILED,
                    new TransferStateStore(storage.configuration()).read(peer.ready.transferId()).state());
            assertFalse(Files.exists(storage.partPath(peer.ready.transferId())));
            assertFalse(Files.exists(storage.completedPath(peer.ready.fileId())));
            assertTrue(catalog.listFiles().isEmpty());
        }
    }

    @Test
    void sourceMutationAfterHashDoesNotReportSuccess() throws Exception {
        Path source = Files.writeString(temporary.resolve("changing"), "abc");
        try (Running running = start(); TcpClient client = running.client()) {
            List<UploadProgress> progress = new ArrayList<>();
            ServerResponseException error = assertThrows(ServerResponseException.class,
                    () -> new UploadService(client).uploadFile(source, p -> {
                        progress.add(p);
                        if (p.bytesTransferred() == 0) {
                            try {
                                Files.writeString(source, "BAD");
                            } catch (IOException ex) {
                                throw new UncheckedIOException(ex);
                            }
                        }
                    }));
            assertEquals(ErrorCode.HASH_MISMATCH, error.code());
            assertFalse(client.isConnected());
            assertTrue(progress.stream().noneMatch(p -> p.percentage() == 100));
            assertTrue(catalog.listFiles().isEmpty());
        }
    }

    @Test
    void resetMidUploadPersistsInterruptedCommittedOffsetAndKeepsPartHidden() throws Exception {
        try (Running running = start()) {
            UUID transferId, fileId;
            try (Peer peer = new Peer(running)) {
                peer.begin("partial", 6, hash(new byte[] { 0, 1, 2, 3, 4, 5 }));
                transferId = peer.ready.transferId();
                fileId = peer.ready.fileId();
                peer.chunk(0, new byte[] { 0, 1, 2 });
                peer.ack(3);
                peer.socket.setSoLinger(true, 0);
            }
            TransferSnapshot snapshot = awaitState(transferId, TransferState.INTERRUPTED);
            assertEquals(3, snapshot.nextOffset());
            assertEquals(3, Files.size(storage.partPath(transferId)));
            assertFalse(Files.exists(storage.completedPath(fileId)));
            assertTrue(catalog.listFiles().isEmpty());
            try (TcpClient client = running.client()) {
                assertTrue(client.listFiles().files().isEmpty());
            }
        }
    }
}
