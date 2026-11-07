package vn.edu.lanfileshare.server.storage;

import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CatalogConcurrencyTest {
    @TempDir
    Path root;

    @Test
    void separateCatalogInstancesCannotOverwriteSameId() throws Exception {
        var storage = new FileStorageService(new StorageConfiguration(root));
        var first = new JsonFileCatalog(storage);
        var second = new JsonFileCatalog(storage);
        var file = storage.importVerified(Files.writeString(root.resolve("input"), "verified"), "same.txt");
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successes = new AtomicInteger(), rejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            java.util.List<Future<?>> tasks = new java.util.ArrayList<>();
            for (var catalog : java.util.List.of(first, second))
                tasks.add(pool.submit(() -> {
                    try {
                        barrier.await(3, TimeUnit.SECONDS);
                        try {
                            catalog.registerCompletedFile(file);
                            successes.incrementAndGet();
                        } catch (java.io.IOException e) {
                            rejected.incrementAndGet();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            for (var task : tasks)
                task.get(5, TimeUnit.SECONDS);
            assertEquals(1, successes.get());
            assertEquals(1, rejected.get());
            assertEquals(java.util.List.of(file), first.listFiles());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
