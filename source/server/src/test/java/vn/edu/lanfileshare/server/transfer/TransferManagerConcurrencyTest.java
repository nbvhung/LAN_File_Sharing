package vn.edu.lanfileshare.server.transfer;

import java.util.*;
import java.util.concurrent.*;
import java.time.Duration;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.server.storage.StorageConfiguration;

class TransferManagerConcurrencyTest {
    @TempDir
    Path root;

    static class Controlled implements ManagedTransfer {
        final UUID id = UUID.randomUUID(), file = UUID.randomUUID(), owner = UUID.randomUUID();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

        public TransferSnapshot snapshot() {
            return new TransferSnapshot(1, id, file, UUID.randomUUID(), owner, "x", 1, "a".repeat(64), Direction.UPLOAD,
                    0, TransferState.INTERRUPTED, System.currentTimeMillis());
        }

        public void resume(UUID owner, UUID request) throws java.io.IOException {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS))
                    throw new java.io.IOException("gate timeout");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(e);
            }
        }

        public void interrupted() {
        }

        public void close() {
        }
    }

    @Test
    void blockedResumeOfOneTransferDoesNotLockOtherTransfers() throws Exception {
        try (var manager = new TransferSessionManager(new StorageConfiguration(root))) {
            Controlled slow = new Controlled();
            manager.register(slow);
            manager.release(slow, slow.owner);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = pool.submit(() -> {
                    try {
                        manager.claim(new ResumeQuery(1, UUID.randomUUID(), slow.id, Direction.UPLOAD, slow.file, 1,
                                "a".repeat(64)), UUID.randomUUID());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                assertTrue(slow.entered.await(2, TimeUnit.SECONDS));
                pool.submit(() -> {
                    try {
                        Controlled other = new Controlled();
                        manager.register(other);
                        assertEquals(other.id, manager.snapshot(other.id).transferId());
                        manager.release(other, other.owner);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).get(2, TimeUnit.SECONDS);
                slow.release.countDown();
                first.get(3, TimeUnit.SECONDS);
            } finally {
                slow.release.countDown();
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }
}
