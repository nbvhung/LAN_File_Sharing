package vn.edu.lanfileshare.server.transfer;

import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.FileChunk;
import vn.edu.lanfileshare.server.storage.*;

class TransferSessionTest {
    @TempDir
    Path directory;

    @Test
    void initializedProgressAndInterruptionArePersistedBeforeAckCanBeSent() throws Exception {
        FileStorageService storage = new FileStorageService(new StorageConfiguration(directory));
        TransferStateStore states = new TransferStateStore(storage.configuration());
        UUID request = UUID.randomUUID();
        try (TransferSession transfer = new TransferSession(UUID.randomUUID(),
                new UploadInit(1, request, "safe", 6, "0".repeat(64)), storage, states)) {
            TransferSnapshot initial = states.read(transfer.snapshot().transferId());
            assertEquals(TransferState.INITIALIZED, initial.state());
            assertEquals(0, initial.nextOffset());
            transfer.append(new FileChunk(initial.transferId(), 0, new byte[] { 1, 2, 3 }));
            TransferSnapshot progress = states.read(initial.transferId());
            assertEquals(3, progress.nextOffset());
            assertEquals(TransferState.TRANSFERRING, progress.state());
            assertEquals(request, progress.requestId());
            assertEquals(Direction.UPLOAD, progress.direction());
            assertThrows(IllegalStateException.class, transfer::completed);
            transfer.interrupted();
            assertEquals(TransferState.INTERRUPTED, states.read(initial.transferId()).state());
            assertEquals(3, Files.size(storage.partPath(initial.transferId())));
            assertTrue(new JsonFileCatalog(storage).listFiles().isEmpty());
        }
    }

    @Test
    void failedSessionDeletesPartAndCannotAppend() throws Exception {
        FileStorageService storage = new FileStorageService(new StorageConfiguration(directory));
        TransferStateStore states = new TransferStateStore(storage.configuration());
        try (TransferSession transfer = new TransferSession(UUID.randomUUID(),
                new UploadInit(1, UUID.randomUUID(), "safe", 1, "0".repeat(64)), storage, states)) {
            UUID id = transfer.snapshot().transferId();
            transfer.failed();
            assertEquals(TransferState.FAILED, states.read(id).state());
            assertFalse(Files.exists(storage.partPath(id)));
            assertThrows(IllegalStateException.class, () -> transfer.append(new FileChunk(id, 0, new byte[] { 1 })));
            transfer.interrupted();
            assertEquals(TransferState.FAILED, states.read(id).state());
        }
    }

    @Test
    void independentStateStoresReadDuringAtomicProgressReplacement() throws Exception {
        FileStorageService storage = new FileStorageService(new StorageConfiguration(directory));
        TransferStateStore writer = new TransferStateStore(storage.configuration());
        TransferStateStore reader = new TransferStateStore(storage.configuration());
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (TransferSession transfer = new TransferSession(UUID.randomUUID(),
                new UploadInit(1, UUID.randomUUID(), "safe", 64, "0".repeat(64)), storage, writer)) {
            UUID id = transfer.snapshot().transferId();
            var reading = executor.submit(() -> {
                for (int i = 0; i < 1000; i++) {
                    TransferSnapshot snapshot = reader.read(id);
                    assertTrue(snapshot.nextOffset() >= 0 && snapshot.nextOffset() <= 64);
                }
                return null;
            });
            for (int i = 0; i < 64; i++)
                transfer.append(new FileChunk(id, i, new byte[] { 1 }));
            reading.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(64, reader.read(id).nextOffset());
            transfer.interrupted();
            assertEquals(TransferState.INTERRUPTED, reader.read(id).state());
        } finally {
            executor.shutdownNow();
        }
    }
}
