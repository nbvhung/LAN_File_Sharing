package vn.edu.lanfileshare.server.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static vn.edu.lanfileshare.common.error.ErrorCode.*;

import java.io.ByteArrayOutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.server.storage.FileCatalog;
import vn.edu.lanfileshare.server.storage.FileMetadata;
import vn.edu.lanfileshare.server.transfer.TransferService.*;

class TransferServiceTest {
    @TempDir Path root;
    FileCatalog catalog;
    TransferService service;
    UUID session;

    @BeforeEach void setup() throws Exception {
        catalog = new FileCatalog(root);
        service = new TransferService(catalog);
        session = UUID.randomUUID();
    }

    @Test void binaryUploadDownloadAndCompletedCatalogSurviveReopening() throws Exception {
        byte[] data = randomBytes(200_003);
        FileMetadata file = upload(session, "báo cáo.bin", data);
        assertArrayEquals(data, download(session, file, true));
        assertEquals(List.of(file), new FileCatalog(root).list());
        assertEquals(file.sha256(), hash(Files.readAllBytes(content(file))));
        try (var paths = Files.list(root.resolve("partial"))) { assertEquals(0, paths.count()); }
    }

    @Test void emptyFilesCompleteWithoutAnyChunkInEitherDirection() throws Exception {
        StartResult result = service.initUpload(session, "empty", 0, hash(new byte[0]));
        assertEquals(0, result.ready().nextOffset());
        assertTrue(result.completion().isPresent());
        FileMetadata file = service.listFiles().getFirst();
        Ready ready = service.initDownload(session, file.fileId());
        service.completeDownload(session, completion(ready));
        assertCode(TRANSFER_NOT_FOUND, () -> service.resume(session, resume(ready)));
    }

    @Test void partialFilesAreHiddenAndMetadataIsCommittedBeforeAck() throws Exception {
        byte[] data = randomBytes(20);
        Ready ready = service.initUpload(session, "file", data.length, hash(data)).ready();
        UploadProgress progress = send(session, ready, 0, Arrays.copyOf(data, 7));
        assertEquals(7, progress.ack().nextOffset());
        assertTrue(progress.completion().isEmpty());
        assertTrue(service.listFiles().isEmpty());
        assertEquals(7, Files.size(part(ready)));
        Properties metadata = metadata(ready);
        assertEquals("7", metadata.getProperty("nextOffset"));
        assertEquals("TRANSFERRING", metadata.getProperty("state"));
        assertEquals(TransferState.TRANSFERRING, service.snapshot(ready.transferId()).state());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../x", "a/b", "a\\b", "a..b", "\nfile", "", " ", "a\u007fb"})
    void rejectsUnsafeNamesWithoutCreatingParts(String name) throws Exception {
        assertCode(INVALID_NAME, () -> service.initUpload(session, name, 1, hash(new byte[1])));
        try (var paths = Files.list(root.resolve("partial"))) { assertEquals(0, paths.count()); }
    }

    @Test void validatesUtf8ByteLengthRatherThanCharacterCount() throws Exception {
        assertCode(INVALID_NAME, () -> service.initUpload(session, "ệ".repeat(86), 0, hash(new byte[0])));
        assertTrue(service.initUpload(session, "ệ".repeat(85), 0, hash(new byte[0])).completion().isPresent());
    }

    @Test void duplicateNamesGetIndependentIdsAndNeverOverwrite() throws Exception {
        FileMetadata first = upload(session, "same.bin", new byte[]{1});
        FileMetadata second = upload(session, "same.bin", new byte[]{2});
        assertNotEquals(first.fileId(), second.fileId());
        assertEquals(2, service.listFiles().size());
        assertArrayEquals(new byte[]{1}, download(session, first, true));
        assertArrayEquals(new byte[]{2}, download(session, second, true));
        assertThrows(UnsupportedOperationException.class, () -> service.listFiles().clear());
    }

    @Test void uploadDuplicateIsIdempotentAndOverlapOrSkippedOffsetIsRejected() throws Exception {
        byte[] data = randomBytes(20);
        Ready ready = service.initUpload(session, "x", data.length, hash(data)).ready();
        send(session, ready, 0, Arrays.copyOf(data, 10));
        assertEquals(10, send(session, ready, 0, Arrays.copyOf(data, 10)).ack().nextOffset());
        assertEquals(10, send(session, ready, 3, new byte[2]).ack().nextOffset());
        assertCode(OFFSET_MISMATCH, () -> send(session, ready, 9, new byte[2]));
        assertCode(OFFSET_MISMATCH, () -> send(session, ready, 11, new byte[1]));
        assertEquals(10, Files.size(part(ready)));
        assertTrue(send(session, ready, 10, Arrays.copyOfRange(data, 10, 20)).completion().isPresent());
    }

    @Test void chunkBoundsAndMalformedPayloadsDoNotWriteData() throws Exception {
        Ready ready = service.initUpload(session, "x", 100, hash(new byte[100])).ready();
        assertCode(INVALID_FRAME, () -> send(session, ready, -1, new byte[1]));
        assertCode(INVALID_FRAME, () -> send(session, ready, 0, new byte[0]));
        assertCode(INVALID_FRAME, () -> send(session, ready, 0, new byte[65_537]));
        assertCode(INVALID_FRAME, () -> send(session, ready, Long.MAX_VALUE, new byte[1]));
        assertCode(INVALID_FRAME, () -> send(session, ready, 100, new byte[1]));
        assertCode(INVALID_FRAME, () -> service.acceptUploadChunk(session, null));
        assertCode(INVALID_FRAME, () -> send(session, ready, 0, null));
        assertEquals(0, Files.size(part(ready)));
    }

    @Test void hashMismatchDeletesPartialAndNeverPublishesFile() throws Exception {
        Ready ready = service.initUpload(session, "bad", 3, hash(new byte[]{1, 2, 3})).ready();
        assertCode(HASH_MISMATCH, () -> send(session, ready, 0, new byte[]{3, 2, 1}));
        assertTrue(service.listFiles().isEmpty());
        assertFalse(Files.exists(part(ready)));
        assertCode(TRANSFER_NOT_FOUND, () -> service.resume(session, resume(ready)));
        assertCode(HASH_MISMATCH, () -> service.initUpload(session, "empty-bad", 0, "0".repeat(64)));
    }

    @Test void uploadResumesFromCommittedOffsetAfterDisconnectAndLostAck() throws Exception {
        byte[] data = randomBytes(140_000);
        Ready ready = service.initUpload(session, "resume", data.length, hash(data)).ready();
        send(session, ready, 0, Arrays.copyOf(data, 65_536)); // pretend ACK was lost
        service.interrupt(session);
        assertEquals(TransferState.INTERRUPTED, service.snapshot(ready.transferId()).state());
        UUID reconnected = UUID.randomUUID();
        Ready resumed = service.resume(reconnected, resume(ready)).ready();
        assertEquals(65_536, resumed.nextOffset());
        assertCode(SERVER_BUSY, () -> send(session, ready, 65_536, new byte[1]));
        send(reconnected, ready, 65_536, Arrays.copyOfRange(data, 65_536, 131_072));
        send(reconnected, ready, 131_072, Arrays.copyOfRange(data, 131_072, data.length));
        assertArrayEquals(data, download(reconnected, service.listFiles().getFirst(), false));
        assertCode(TRANSFER_NOT_FOUND, () -> service.resume(reconnected, resume(ready)));
    }

    @Test void uploadResumeTruncatesUnacknowledgedTailAndDetectsShortPartial() throws Exception {
        Ready ready = service.initUpload(session, "resume", 10, hash(new byte[10])).ready();
        send(session, ready, 0, new byte[3]);
        service.interrupt(session);
        Files.write(part(ready), new byte[8]);
        UUID reconnected = UUID.randomUUID();
        assertEquals(3, service.resume(reconnected, resume(ready)).ready().nextOffset());
        assertEquals(3, Files.size(part(ready)));
        service.interrupt(reconnected);
        Files.write(part(ready), new byte[2]);
        assertCode(TRANSFER_CHANGED, () -> service.resume(UUID.randomUUID(), resume(ready)));
        assertTrue(service.listFiles().isEmpty());
    }

    @Test void resumeChecksAllMetadataAndDoesNotStealAnotherConnection() throws Exception {
        Ready ready = service.initUpload(session, "x", 3, hash(new byte[3])).ready();
        UUID other = UUID.randomUUID();
        assertCode(SERVER_BUSY, () -> service.resume(other, resume(ready)));
        assertCode(TRANSFER_CHANGED, () -> service.resume(session,
                new ResumeRequest(ready.transferId(), Direction.DOWNLOAD, ready.fileId(), ready.size(), ready.sha256())));
        assertCode(TRANSFER_CHANGED, () -> service.resume(session,
                new ResumeRequest(ready.transferId(), Direction.UPLOAD, UUID.randomUUID(), ready.size(), ready.sha256())));
        assertCode(TRANSFER_CHANGED, () -> service.resume(session,
                new ResumeRequest(ready.transferId(), Direction.UPLOAD, ready.fileId(), 2, ready.sha256())));
        assertCode(TRANSFER_CHANGED, () -> service.resume(session,
                new ResumeRequest(ready.transferId(), Direction.UPLOAD, ready.fileId(), 3, "0".repeat(64))));
        assertEquals(0, service.snapshot(ready.transferId()).transfer().nextOffset());
    }

    @Test void downloadRequiresInitialAckAndEnforcesSingleOutstandingChunk() throws Exception {
        FileMetadata file = upload(session, "x", randomBytes(150_000));
        Ready ready = service.initDownload(session, file.fileId());
        assertEquals(0, service.snapshot(ready.transferId()).transfer().nextOffset());
        assertCode(OFFSET_MISMATCH, () -> service.acknowledgeDownload(session, ready.transferId(), 1));
        FileChunk first = service.acknowledgeDownload(session, ready.transferId(), 0).orElseThrow();
        assertEquals(65_536, first.data().length);
        FileChunk duplicate = service.acknowledgeDownload(session, ready.transferId(), 0).orElseThrow();
        assertArrayEquals(first.data(), duplicate.data());
        assertCode(OFFSET_MISMATCH, () -> service.acknowledgeDownload(session, ready.transferId(), 65_535));
        assertCode(OFFSET_MISMATCH, () -> service.acknowledgeDownload(session, ready.transferId(), -1));
        assertCode(OFFSET_MISMATCH, () -> service.acknowledgeDownload(session, ready.transferId(), Long.MAX_VALUE));
        FileChunk second = service.acknowledgeDownload(session, ready.transferId(), 65_536).orElseThrow();
        assertEquals(65_536, second.offset());
        byte[] copy = second.data();
        copy[0] ^= 1;
        assertFalse(Arrays.equals(copy, second.data()));
        assertCode(OFFSET_MISMATCH, () -> service.completeDownload(session, completion(ready)));
    }

    @Test void downloadResumeUsesClientPartialOffsetEvenWhenLastAckWasLost() throws Exception {
        byte[] data = randomBytes(160_000);
        FileMetadata file = upload(session, "x", data);
        Ready ready = service.initDownload(session, file.fileId());
        FileChunk first = service.acknowledgeDownload(session, ready.transferId(), 0).orElseThrow();
        service.interrupt(session); // client wrote first chunk, ACK never reached server
        UUID reconnect = UUID.randomUUID();
        Ready resumed = service.resume(reconnect, resume(ready)).ready();
        assertEquals(0, resumed.nextOffset()); // server has not received that ACK
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        received.write(first.data());
        long offset = first.data().length;
        while (offset < data.length) {
            FileChunk chunk = service.acknowledgeDownload(reconnect, ready.transferId(), offset).orElseThrow();
            assertEquals(offset, chunk.offset());
            received.write(chunk.data());
            offset += chunk.data().length;
        }
        service.completeDownload(reconnect, completion(ready)); // last ACK is optional
        assertArrayEquals(data, received.toByteArray());
    }

    @Test void downloadResumeCanRewindOrResumeAtEof() throws Exception {
        FileMetadata file = upload(session, "x", randomBytes(100_000));
        Ready ready = service.initDownload(session, file.fileId());
        service.acknowledgeDownload(session, ready.transferId(), 0);
        service.acknowledgeDownload(session, ready.transferId(), 65_536);
        service.interrupt(session);
        UUID reconnect = UUID.randomUUID();
        service.resume(reconnect, resume(ready));
        assertEquals(7, service.acknowledgeDownload(reconnect, ready.transferId(), 7).orElseThrow().offset());
        service.interrupt(reconnect);
        UUID third = UUID.randomUUID();
        service.resume(third, resume(ready));
        assertTrue(service.acknowledgeDownload(third, ready.transferId(), file.size()).isEmpty());
        service.completeDownload(third, completion(ready));
    }

    @Test void downloadDetectsSourceChangedAtInitResumeAndCompletion() throws Exception {
        FileMetadata file = upload(session, "x", new byte[]{1, 2, 3});
        Ready ready = service.initDownload(session, file.fileId());
        service.interrupt(session);
        Files.write(content(file), new byte[]{3, 2, 1}); // same size, different hash
        assertCode(TRANSFER_CHANGED, () -> service.resume(UUID.randomUUID(), resume(ready)));
        assertCode(TRANSFER_CHANGED, () -> service.initDownload(session, file.fileId()));
        Files.write(content(file), new byte[]{1, 2, 3});
        Ready second = service.initDownload(session, file.fileId());
        service.acknowledgeDownload(session, second.transferId(), 0);
        Files.write(content(file), new byte[]{0, 0, 0});
        assertCode(TRANSFER_CHANGED, () -> service.completeDownload(session, completion(second)));
    }

    @Test void rejectsWrongDirectionMissingFileAndInvalidCompletion() throws Exception {
        assertCode(NOT_FOUND, () -> service.initDownload(session, UUID.randomUUID()));
        assertCode(BAD_REQUEST, () -> service.initDownload(session, null));
        Ready upload = service.initUpload(session, "x", 2, hash(new byte[2])).ready();
        assertCode(BAD_REQUEST, () -> service.acknowledgeDownload(session, upload.transferId(), 0));
        FileMetadata file = upload(session, "done", new byte[]{1});
        Ready download = service.initDownload(session, file.fileId());
        assertCode(BAD_REQUEST, () -> send(session, download, 0, new byte[]{1}));
        assertCode(TRANSFER_CHANGED, () -> service.completeDownload(session,
                new Completion(download.transferId(), file.fileId(), 1, "0".repeat(64))));
        assertCode(SERVER_BUSY, () -> service.abort(UUID.randomUUID(), download.transferId()));
        service.abort(session, download.transferId());
        assertCode(TRANSFER_NOT_FOUND, () -> service.resume(session, resume(download)));
        assertEquals(1, service.listFiles().size()); // aborting download never deletes its source
    }

    @Test void resourceLimitsExpiryAndDisconnectRefreshUseInjectedClock() throws Exception {
        MutableClock clock = new MutableClock();
        service = new TransferService(catalog, 10, 1, Duration.ofHours(24), clock);
        assertCode(FILE_TOO_LARGE, () -> service.initUpload(session, "x", 11, hash(new byte[11])));
        assertCode(BAD_REQUEST, () -> service.initUpload(session, "x", -1, hash(new byte[0])));
        assertCode(BAD_REQUEST, () -> service.initUpload(session, "x", 1, "ABC"));
        Ready first = service.initUpload(session, "x", 10, hash(new byte[10])).ready();
        assertCode(SERVER_BUSY, () -> service.initUpload(session, "x", 1, hash(new byte[1])));
        clock.advance(Duration.ofHours(23));
        service.interrupt(session);
        clock.advance(Duration.ofHours(2));
        assertEquals(0, service.cleanupExpired());
        clock.advance(Duration.ofHours(22));
        assertEquals(1, service.cleanupExpired());
        assertFalse(Files.exists(part(first)));
        assertCode(TRANSFER_NOT_FOUND, () -> service.resume(session, resume(first)));
        assertTrue(service.initUpload(session, "new", 0, hash(new byte[0])).completion().isPresent());
        assertTrue(service.initUpload(session, "another", 0, hash(new byte[0])).completion().isPresent());
    }

    @Test void oldActiveIdsAreNotRecoveredAfterServiceRestart() throws Exception {
        Ready ready = service.initUpload(session, "x", 5, hash(new byte[5])).ready();
        send(session, ready, 0, new byte[1]);
        service.interrupt(session);
        TransferService restarted = new TransferService(new FileCatalog(root));
        assertCode(TRANSFER_NOT_FOUND, () -> restarted.resume(session, resume(ready)));
        assertTrue(restarted.listFiles().isEmpty());
    }

    @Test void storageFailureReturnsSafeErrorAndConservativeOffset() throws Exception {
        Ready ready = service.initUpload(session, "x", 3, hash(new byte[3])).ready();
        Files.delete(part(ready));
        TransferException ex = assertCode(IO_ERROR, () -> send(session, ready, 0, new byte[1]));
        assertFalse(ex.getMessage().contains(root.toString()));
        assertEquals(0, service.snapshot(ready.transferId()).transfer().nextOffset());
        assertEquals(TransferState.INTERRUPTED, service.snapshot(ready.transferId()).state());
    }

    @Test void metadataWriteFailureRollsBackBytesBeforeAnyAck() throws Exception {
        Ready ready = service.initUpload(session, "x", 10, hash(new byte[10])).ready();
        send(session, ready, 0, new byte[3]);
        Path metadata = root.resolve("partial").resolve(ready.transferId() + ".properties");
        Files.delete(metadata);
        Files.createDirectory(metadata);
        Path blocker = metadata.resolve("blocker");
        Files.writeString(blocker, "block replacement");
        assertCode(IO_ERROR, () -> send(session, ready, 3, new byte[2]));
        assertEquals(3, Files.size(part(ready)));
        assertEquals(3, service.snapshot(ready.transferId()).transfer().nextOffset());
        Files.delete(blocker);
        Files.delete(metadata);
        UUID reconnect = UUID.randomUUID();
        assertEquals(3, service.resume(reconnect, resume(ready)).ready().nextOffset());
        assertTrue(send(reconnect, ready, 3, new byte[7]).completion().isPresent());
    }

    @Test void resumeAtEofRetriesPublicationAfterFinalizationIoFailure() throws Exception {
        Ready ready = service.initUpload(session, "x", 3, hash(new byte[3])).ready();
        Path blocker = root.resolve("completed").resolve(ready.fileId() + ".bin");
        Files.createDirectory(blocker);
        assertCode(IO_ERROR, () -> send(session, ready, 0, new byte[3]));
        assertEquals(3, service.snapshot(ready.transferId()).transfer().nextOffset());
        assertTrue(service.listFiles().isEmpty());
        Files.delete(blocker);
        StartResult result = service.resume(UUID.randomUUID(), resume(ready));
        assertEquals(3, result.ready().nextOffset());
        assertTrue(result.completion().isPresent());
        assertEquals(1, service.listFiles().size());
    }

    @ParameterizedTest @ValueSource(ints = {4, 8})
    void independentClientsUploadAndDownloadConcurrently(int clients) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(clients);
        try (var pool = Executors.newFixedThreadPool(clients)) {
            List<Callable<UUID>> jobs = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                final int seed = i;
                jobs.add(() -> {
                    UUID connection = UUID.randomUUID();
                    byte[] data = new byte[1_048_576 + seed];
                    new Random(seed).nextBytes(data);
                    barrier.await();
                    FileMetadata file = upload(connection, "same-name", data);
                    assertArrayEquals(data, download(connection, file, true));
                    service.interrupt(connection);
                    return file.fileId();
                });
            }
            var results = pool.invokeAll(jobs);
            var ids = new java.util.HashSet<UUID>();
            for (var result : results) ids.add(result.get());
            assertEquals(clients, ids.size());
            assertEquals(clients, service.listFiles().size());
        }
    }

    @Test void concurrentDuplicateChunksAreSerializedByTransferLock() throws Exception {
        byte[] data = randomBytes(100);
        Ready ready = service.initUpload(session, "x", data.length, hash(data)).ready();
        CyclicBarrier barrier = new CyclicBarrier(8);
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Long>> jobs = new ArrayList<>();
            for (int i = 0; i < 8; i++) jobs.add(() -> {
                barrier.await();
                return send(session, ready, 0, Arrays.copyOf(data, 50)).ack().nextOffset();
            });
            for (var result : pool.invokeAll(jobs)) assertEquals(50, result.get());
        }
        assertEquals(50, Files.size(part(ready)));
        send(session, ready, 50, Arrays.copyOfRange(data, 50, 100));
        assertArrayEquals(data, download(session, service.listFiles().getFirst(), true));
    }

    private FileMetadata upload(UUID connection, String name, byte[] data) throws Exception {
        Ready ready = service.initUpload(connection, name, data.length, hash(data)).ready();
        for (int offset = 0; offset < data.length; offset += 65_536) {
            int end = Math.min(offset + 65_536, data.length);
            UploadProgress progress = send(connection, ready, offset, Arrays.copyOfRange(data, offset, end));
            assertEquals(end, progress.ack().nextOffset());
            assertEquals(end == data.length, progress.completion().isPresent());
        }
        return catalog.find(ready.fileId()).orElseThrow();
    }

    private byte[] download(UUID connection, FileMetadata file, boolean finalAck) throws Exception {
        Ready ready = service.initDownload(connection, file.fileId());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long offset = 0;
        while (offset < ready.size()) {
            FileChunk chunk = service.acknowledgeDownload(connection, ready.transferId(), offset).orElseThrow();
            assertEquals(offset, chunk.offset());
            out.write(chunk.data());
            offset += chunk.data().length;
        }
        if (finalAck) assertTrue(service.acknowledgeDownload(connection, ready.transferId(), offset).isEmpty());
        service.completeDownload(connection, completion(ready));
        return out.toByteArray();
    }

    private UploadProgress send(UUID connection, Ready ready, long offset, byte[] data) throws TransferException {
        return service.acceptUploadChunk(connection, new FileChunk(ready.transferId(), offset, data));
    }

    private Path part(Ready ready) { return root.resolve("partial").resolve(ready.transferId() + ".part"); }
    private Path content(FileMetadata file) { return root.resolve("completed").resolve(file.fileId() + ".bin"); }

    private Properties metadata(Ready ready) throws Exception {
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(root.resolve("partial").resolve(ready.transferId() + ".properties"),
                StandardCharsets.UTF_8)) { p.load(reader); }
        return p;
    }

    private static ResumeRequest resume(Ready ready) {
        return new ResumeRequest(ready.transferId(), ready.direction(), ready.fileId(), ready.size(), ready.sha256());
    }

    private static Completion completion(Ready ready) {
        return new Completion(ready.transferId(), ready.fileId(), ready.size(), ready.sha256());
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(42).nextBytes(bytes);
        return bytes;
    }

    private static String hash(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static TransferException assertCode(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        TransferException ex = assertThrows(TransferException.class, action);
        assertEquals(code, ex.code());
        return ex;
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
