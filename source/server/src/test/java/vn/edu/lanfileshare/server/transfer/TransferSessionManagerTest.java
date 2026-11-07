package vn.edu.lanfileshare.server.transfer;

import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.common.io.FileHashes;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.server.storage.*;

class TransferSessionManagerTest {
    @TempDir
    Path directory;

    @Test
    void staleDisconnectCannotReleaseANewOwnersLease() throws Exception {
        FileStorageService storage = new FileStorageService(new StorageConfiguration(directory));
        Path expected = Files.writeString(directory.resolve("source"), "abcdef");
        UUID oldOwner = UUID.randomUUID(), newOwner = UUID.randomUUID();
        try (TransferSessionManager manager = new TransferSessionManager(storage.configuration())) {
            TransferSession session = new TransferSession(oldOwner,
                    new UploadInit(1, UUID.randomUUID(), "file", 6, FileHashes.sha256(expected)), storage,
                    new TransferStateStore(storage.configuration()));
            manager.register(session);
            session.append(new vn.edu.lanfileshare.common.protocol.FileChunk(session.snapshot().transferId(), 0,
                    new byte[] { 97, 98, 99 }));
            manager.release(session, oldOwner);
            var snapshot = session.snapshot();
            ManagedTransfer claimed = manager.claim(new ResumeQuery(1, UUID.randomUUID(), snapshot.transferId(),
                    Direction.UPLOAD, snapshot.fileId(), 6, snapshot.sha256()), newOwner);
            assertSame(session, claimed);
            manager.release(session, oldOwner);
            assertTrue(manager.owns(session, newOwner));
            assertFalse(manager.owns(session, oldOwner));
            assertEquals(3, session.snapshot().nextOffset());
        }
    }

    @Test
    void corruptOldStagingJsonDoesNotBlockNewRegistrations() throws Exception {
        FileStorageService storage = new FileStorageService(new StorageConfiguration(directory));
        Files.writeString(storage.configuration().staging().resolve(UUID.randomUUID() + ".json"), "broken");
        try (TransferSessionManager manager = new TransferSessionManager(storage.configuration())) {
            TransferSession session = new TransferSession(UUID.randomUUID(),
                    new UploadInit(1, UUID.randomUUID(), "empty", 0,
                            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                    storage, new TransferStateStore(storage.configuration()));
            manager.register(session);
            assertEquals(session.snapshot().transferId(),
                    manager.snapshot(session.snapshot().transferId()).transferId());
        }
    }
}
