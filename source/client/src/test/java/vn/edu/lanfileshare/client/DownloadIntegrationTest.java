package vn.edu.lanfileshare.client;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
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

class DownloadIntegrationTest {
    @TempDir
    Path directory;
    private FileStorageService storage;
    private JsonFileCatalog catalog;

    @BeforeAll
    static void warmEntropy() {
        UUID.randomUUID();
    }

    @BeforeEach
    void setup() throws Exception {
        storage = new FileStorageService(new StorageConfiguration(directory.resolve("storage")));
        catalog = new JsonFileCatalog(storage);
    }

    record Running(TcpServer server, TransferCoordinator transfers) implements AutoCloseable {
        public void close() throws IOException {
            try {
                server.close();
            } finally {
                transfers.close();
            }
        }

        TcpClient client() throws IOException {
            TcpClient c = new TcpClient(5000);
            c.connect("127.0.0.1", server.port());
            return c;
        }
    }

    private Running start() throws IOException {
        var transfers = new TransferCoordinator(storage, catalog, UploadService.DEFAULT_MAX_FILE_BYTES);
        var server = new TcpServer(InetAddress.getLoopbackAddress(), 0, 8, 5000,
                new RequestDispatcher(catalog, transfers));
        server.start();
        return new Running(server, transfers);
    }

    private Path binary(int size) throws IOException {
        Path source = directory.resolve("source-" + size + ".bin");
        Random random = new Random(42);
        try (OutputStream output = Files.newOutputStream(source)) {
            byte[] buffer = new byte[65536];
            for (int remaining = size; remaining > 0; remaining -= Math.min(remaining, buffer.length)) {
                random.nextBytes(buffer);
                buffer[0] = 0;
                output.write(buffer, 0, Math.min(remaining, buffer.length));
            }
        }
        return source;
    }

    private void roundTrip(Path source) throws Exception {
        Path target = Files.createDirectory(directory.resolve("downloads")).resolve("chosen-local-name.bin");
        try (Running running = start(); TcpClient client = running.client()) {
            TransferResult uploaded = new UploadService(client).uploadFile(source, p -> {
            });
            FileInfo listed = client.listFiles().files().stream()
                    .filter(f -> f.fileId().equals(uploaded.file().fileId())).findFirst().orElseThrow();
            List<DownloadProgress> progress = new ArrayList<>();
            DownloadResult result = new DownloadService(client).downloadFile(listed.fileId(), target, progress::add);
            assertEquals(-1, Files.mismatch(source, result.destination()));
            assertEquals(FileHashes.sha256(source), FileHashes.sha256(result.destination()));
            assertEquals(listed, result.file());
            assertEquals(source.getFileName().toString(), result.file().name());
            assertEquals(target.toAbsolutePath(), result.destination());
            assertEquals(TransferStatus.INITIALIZED, progress.getFirst().status());
            assertEquals(TransferStatus.COMPLETED, progress.getLast().status());
            assertEquals(100, progress.getLast().percentage());
            assertTrue(progress.stream().anyMatch(p -> p.status() == TransferStatus.VERIFYING));
            long previous = 0;
            for (DownloadProgress event : progress) {
                assertTrue(event.bytesTransferred() >= previous);
                previous = event.bytesTransferred();
                assertEquals(Files.size(source), event.totalBytes());
                if (event.status() != TransferStatus.COMPLETED)
                    assertTrue(event.percentage() < 100);
            }
            assertEquals(1, client.listFiles().files().size()); // Barrier: server has processed COMPLETE.
            assertEquals(TransferState.COMPLETED,
                    new TransferStateStore(storage.configuration()).read(result.transferId()).state());
            // Upload remains usable after download on the very same connection.
            new UploadService(client).uploadFile(source, p -> {
            });
            assertEquals(2, client.listFiles().files().size());
            try (var entries = Files.list(target.getParent())) {
                assertEquals(List.of(target), entries.toList());
            }
        }
    }

    @Test
    void emptyUploadListDownload() throws Exception {
        roundTrip(Files.createFile(directory.resolve("empty")));
    }

    @Test
    void textUploadListDownload() throws Exception {
        roundTrip(Files.writeString(directory.resolve("hello.txt"), "abc"));
    }

    @Test
    void oneMiBBinaryUploadListDownload() throws Exception {
        roundTrip(binary(1024 * 1024));
    }

    @Test
    void tenMiBBinaryUploadListDownload() throws Exception {
        roundTrip(binary(10 * 1024 * 1024));
    }

    @Test
    void unicodeNameDoesNotControlLocalDestination() throws Exception {
        roundTrip(Files.writeString(directory.resolve("tệp-đọc.txt"), "unicode"));
    }

    @Test
    void unknownFileIdReturnsNotFoundWithoutCreatingOutput() throws Exception {
        Path target = directory.resolve("absent.bin");
        try (Running running = start(); TcpClient client = running.client()) {
            ServerResponseException error = assertThrows(ServerResponseException.class,
                    () -> new DownloadService(client).downloadFile(UUID.randomUUID(), target,
                            p -> fail("No READY received")));
            assertEquals(ErrorCode.NOT_FOUND, error.code());
            assertFalse(Files.exists(target));
            assertFalse(error.getMessage().contains(storage.configuration().root().toString()));
        }
    }

    @Test
    void unregisteredBytesAndMissingCompletedSourceCannotBeDownloaded() throws Exception {
        UUID orphan = UUID.randomUUID();
        Files.writeString(storage.completedPath(orphan), "not registered");
        Files.writeString(storage.configuration().staging().resolve(orphan + ".part"), "pending");
        FileInfo missing = seed();
        Files.delete(storage.completedPath(missing.fileId()));
        try (Running running = start()) {
            for (UUID id : List.of(orphan, missing.fileId())) {
                Path target = directory.resolve("out-" + id);
                try (TcpClient client = running.client()) {
                    ServerResponseException error = assertThrows(ServerResponseException.class,
                            () -> new DownloadService(client).downloadFile(id, target, p -> fail()));
                    assertEquals(ErrorCode.NOT_FOUND, error.code());
                    assertFalse(Files.exists(target));
                }
            }
        }
    }

    @Test
    void existingDestinationAndInvalidParentAreRejectedBeforeRequest() throws Exception {
        Path existing = Files.writeString(directory.resolve("old.txt"), "old content");
        try (Running running = start(); TcpClient client = running.client()) {
            DownloadService service = new DownloadService(client);
            assertThrows(FileAlreadyExistsException.class,
                    () -> service.downloadFile(UUID.randomUUID(), existing, p -> fail()));
            assertThrows(IOException.class,
                    () -> service.downloadFile(UUID.randomUUID(), directory.resolve("missing/out"), p -> fail()));
            assertThrows(NullPointerException.class,
                    () -> service.downloadFile(null, directory.resolve("new"), p -> fail()));
            assertEquals("old content", Files.readString(existing));
            assertTrue(client.listFiles().files().isEmpty());
        }
    }

    @Test
    void destinationCreatedDuringDownloadIsNeverOverwritten() throws Exception {
        Path source = Files.writeString(directory.resolve("source"), "abc"), target = directory.resolve("chosen");
        try (Running running = start(); TcpClient client = running.client()) {
            UUID id = new UploadService(client).uploadFile(source, p -> {
            }).file().fileId();
            List<DownloadProgress> events = new ArrayList<>();
            assertThrows(FileAlreadyExistsException.class,
                    () -> new DownloadService(client).downloadFile(id, target, p -> {
                        events.add(p);
                        if (p.status() == TransferStatus.INITIALIZED) {
                            try {
                                Files.writeString(target, "keep me", StandardOpenOption.CREATE_NEW);
                            } catch (IOException ex) {
                                throw new UncheckedIOException(ex);
                            }
                        }
                    }));
            assertEquals("keep me", Files.readString(target));
            assertTrue(events.stream().noneMatch(p -> p.status() == TransferStatus.COMPLETED));
            assertEquals(TransferStatus.FAILED, events.getLast().status());
        }
    }

    @Test
    void changedServerBytesCauseHashMismatchAndNoPublishedFile() throws Exception {
        Path source = Files.writeString(directory.resolve("source"), "abc"), target = directory.resolve("target");
        try (Running running = start(); TcpClient client = running.client()) {
            UUID id = new UploadService(client).uploadFile(source, p -> {
            }).file().fileId();
            List<DownloadProgress> events = new ArrayList<>();
            IOException error = assertThrows(IOException.class,
                    () -> new DownloadService(client).downloadFile(id, target, p -> {
                        events.add(p);
                        if (p.status() == TransferStatus.INITIALIZED) {
                            try {
                                Files.writeString(storage.completedPath(id), "BAD");
                            } catch (IOException ex) {
                                throw new UncheckedIOException(ex);
                            }
                        }
                    }));
            assertInstanceOf(vn.edu.lanfileshare.common.protocol.ProtocolException.class, error);
            assertEquals(ErrorCode.HASH_MISMATCH,
                    ((vn.edu.lanfileshare.common.protocol.ProtocolException) error).code());
            assertFalse(Files.exists(target));
            assertTrue(events.stream().noneMatch(p -> p.status() == TransferStatus.COMPLETED));
        }
    }

    private FileInfo seed() throws Exception {
        Path source = Files.writeString(directory.resolve("seed"), "abc");
        FileInfo file = storage.importVerified(source, "server-name.txt");
        catalog.registerCompletedFile(file);
        return file;
    }

    final class Peer implements AutoCloseable {
        final Socket socket;
        UUID requestId;
        TransferReady ready;

        Peer(Running running) throws IOException {
            socket = new Socket("127.0.0.1", running.server().port());
            socket.setSoTimeout(5000);
        }

        void send(MessageType type, Object value) throws IOException {
            FrameCodec.write(socket.getOutputStream(), ControlCodec.encode(type, value));
        }

        Frame read() throws IOException {
            return FrameCodec.read(socket.getInputStream());
        }

        void begin(UUID fileId) throws Exception {
            requestId = UUID.randomUUID();
            send(MessageType.DOWNLOAD_INIT, new DownloadInit(1, requestId, fileId));
            Frame response = read();
            assertEquals(MessageType.TRANSFER_READY, response.type());
            ready = ControlCodec.decode(response, TransferReady.class);
            assertEquals(requestId, ready.requestId());
            assertEquals(Direction.DOWNLOAD, ready.direction());
            assertEquals(0, ready.nextOffset());
        }

        void ack(long offset) throws IOException {
            send(MessageType.PROGRESS_ACK, new ProgressAck(1, requestId, ready.transferId(), offset));
        }

        void error(ErrorCode expected) throws Exception {
            Frame frame = read();
            assertEquals(MessageType.ERROR, frame.type());
            ErrorMessage error = ControlCodec.decode(frame, ErrorMessage.class);
            assertEquals(expected, error.code());
            assertEquals(requestId, error.requestId());
        }

        void complete() throws IOException {
            send(MessageType.TRANSFER_COMPLETE, new TransferComplete(1, requestId, ready.transferId(), ready.fileId(),
                    ready.size(), ready.sha256()));
        }

        void barrier() throws Exception {
            UUID id = UUID.randomUUID();
            send(MessageType.LIST_FILES, new ListFiles(1, id));
            assertEquals(id, ControlCodec.decode(read(), FileList.class).requestId());
        }

        public void close() throws IOException {
            socket.close();
        }
    }

    @Test
    void serverWaitsForFirstAckAndRejectsWrongOffsetBeforeSendingMore() throws Exception {
        FileInfo file = seed();
        try (Running running = start(); Peer peer = new Peer(running)) {
            peer.begin(file.fileId());
            peer.socket.setSoTimeout(150);
            assertThrows(SocketTimeoutException.class, () -> peer.socket.getInputStream().read());
            peer.socket.setSoTimeout(5000);
            peer.ack(1);
            peer.error(ErrorCode.OFFSET_MISMATCH);
            peer.ack(0);
            Frame frame = peer.read();
            assertEquals(MessageType.FILE_CHUNK, frame.type());
            FileChunk chunk = FileChunkCodec.decode(frame.payload());
            assertEquals(0, chunk.offset());
            assertEquals(peer.ready.transferId(), chunk.transferId());
            assertArrayEquals(new byte[] { 97, 98, 99 }, chunk.data());
            peer.ack(2);
            peer.error(ErrorCode.OFFSET_MISMATCH);
            peer.ack(3);
            peer.complete();
            peer.barrier();
            assertEquals(TransferState.COMPLETED,
                    new TransferStateStore(storage.configuration()).read(peer.ready.transferId()).state());
        }
    }

    @Test
    void prematureCompletionAndCrossSocketAckAreRejected() throws Exception {
        FileInfo file = seed();
        try (Running running = start(); Peer owner = new Peer(running); Peer other = new Peer(running)) {
            owner.begin(file.fileId());
            other.begin(file.fileId());
            owner.complete();
            owner.error(ErrorCode.BAD_REQUEST);
            other.send(MessageType.PROGRESS_ACK, new ProgressAck(1, other.requestId, owner.ready.transferId(), 0));
            other.error(ErrorCode.TRANSFER_NOT_FOUND);
            owner.ack(0);
            assertEquals(MessageType.FILE_CHUNK, owner.read().type());
            owner.ack(3);
            owner.complete();
            owner.barrier();
        }
    }

    @Test
    void serverRejectsConcurrentUploadWhileDownloadIsActive() throws Exception {
        FileInfo file = seed();
        try (Running running = start(); Peer peer = new Peer(running)) {
            peer.begin(file.fileId());
            UUID uploadRequest = UUID.randomUUID();
            peer.send(MessageType.UPLOAD_INIT, new UploadInit(1, uploadRequest, "new", 0,
                    FileHashes.sha256(Files.createFile(directory.resolve("empty")))));
            ErrorMessage error = ControlCodec.decode(peer.read(), ErrorMessage.class);
            assertEquals(uploadRequest, error.requestId());
            assertEquals(ErrorCode.SERVER_BUSY, error.code());
            peer.ack(0);
            assertEquals(MessageType.FILE_CHUNK, peer.read().type());
            peer.ack(3);
            peer.complete();
            peer.barrier();
        }
    }

    @Test
    void lostWritePermissionAfterReadyIsARealFilesystemError() throws Exception {
        Path source = Files.writeString(directory.resolve("source"), "abc");
        Path folder = Files.createDirectory(directory.resolve("restricted")), target = folder.resolve("target");
        try (Running running = start(); TcpClient client = running.client()) {
            UUID id = new UploadService(client).uploadFile(source, p -> {
            }).file().fileId();
            AclFileAttributeView acl = Files.getFileAttributeView(folder, AclFileAttributeView.class);
            List<AclEntry> originalAcl = acl == null ? null : acl.getAcl();
            Set<PosixFilePermission> originalPosix = acl == null ? Files.getPosixFilePermissions(folder) : null;
            List<DownloadProgress> events = new ArrayList<>();
            try {
                IOException error = assertThrows(IOException.class,
                        () -> new DownloadService(client).downloadFile(id, target, p -> {
                            events.add(p);
                            if (p.status() == TransferStatus.INITIALIZED) {
                                try {
                                    if (acl != null) {
                                        AclEntry deny = AclEntry.newBuilder().setType(AclEntryType.DENY)
                                                .setPrincipal(Files.getOwner(folder))
                                                .setPermissions(AclEntryPermission.WRITE_DATA,
                                                        AclEntryPermission.APPEND_DATA)
                                                .build();
                                        List<AclEntry> rules = new ArrayList<>(originalAcl);
                                        rules.addFirst(deny);
                                        acl.setAcl(rules);
                                    } else {
                                        Set<PosixFilePermission> restricted = new HashSet<>(originalPosix);
                                        restricted.removeAll(Set.of(PosixFilePermission.OWNER_WRITE,
                                                PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE));
                                        Files.setPosixFilePermissions(folder, restricted);
                                    }
                                } catch (IOException ex) {
                                    throw new UncheckedIOException(ex);
                                }
                            }
                        }));
                assertInstanceOf(FileSystemException.class, error);
                assertFalse(Files.exists(target));
                assertEquals(TransferStatus.FAILED, events.getLast().status());
                assertTrue(events.stream().noneMatch(p -> p.status() == TransferStatus.COMPLETED));
            } finally {
                if (acl != null)
                    acl.setAcl(originalAcl);
                else
                    Files.setPosixFilePermissions(folder, originalPosix);
            }
        }
    }
}
