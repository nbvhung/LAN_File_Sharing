package vn.edu.lanfileshare.server.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import vn.edu.lanfileshare.server.storage.StorageConfiguration;

/** Atomic snapshots after data writes, before ACK. Incomplete snapshots are not catalog metadata. */
public final class TransferStateStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Object[] LOCKS = new Object[64];
    static {
        java.util.Arrays.setAll(LOCKS, index -> new Object());
    }
    private final StorageConfiguration configuration;

    private Object lock(UUID id) {
        Path path = configuration.staging().resolve(id + ".json");
        return LOCKS[Math.floorMod(path.hashCode(), LOCKS.length)];
    }

    public TransferStateStore(StorageConfiguration configuration) {
        this.configuration = configuration;
    }

    public void save(TransferSnapshot snapshot) throws IOException {
        synchronized (lock(snapshot.transferId())) {
            saveLocked(snapshot);
        }
    }

    private void saveLocked(TransferSnapshot snapshot) throws IOException {
        configuration.initialize();
        Path target = configuration.staging().resolve(snapshot.transferId() + ".json");
        Path temporary = Files.createTempFile(configuration.staging(), "transfer-", ".tmp");
        try {
            Files.write(temporary, JSON.writeValueAsBytes(snapshot));
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public void delete(UUID transferId) throws IOException {
        synchronized (lock(transferId)) {
            Files.deleteIfExists(configuration.staging().resolve(transferId + ".json"));
        }
    }

    /** Diagnostic/future-resume input only; Phase 3 does not restore or authorize transfers from it. */
    public TransferSnapshot read(UUID transferId) throws IOException {
        // Windows cannot replace an open file. Share bounded striped locks across store instances.
        synchronized (lock(transferId)) {
            try (var input = Files.newInputStream(configuration.staging().resolve(transferId + ".json"),
                    LinkOption.NOFOLLOW_LINKS)) {
                return JSON.readValue(input, TransferSnapshot.class);
            }
        }
    }
}
