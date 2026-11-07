package vn.edu.lanfileshare.client;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.client.network.*;
import vn.edu.lanfileshare.client.transfer.*;
import vn.edu.lanfileshare.common.io.FileHashes;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.*;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.server.network.*;
import vn.edu.lanfileshare.server.storage.*;
import vn.edu.lanfileshare.server.transfer.*;

class MultiClientIntegrationTest {
    @TempDir
    Path root;
    static final List<Map<String, Object>> proof = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void warm() {
        UUID.randomUUID();
    }

    @AfterAll
    static void evidence() throws Exception {
        Files.createDirectories(Path.of("target"));
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValue(Path.of("target/phase6-evidence.json").toFile(), proof);
    }

    final class Running implements AutoCloseable {
        final FileStorageService storage;
        final JsonFileCatalog catalog;
        final TransferCoordinator transfers;
        final TcpServer server;

        Running(int max, int timeout) throws Exception {
            storage = new FileStorageService(new StorageConfiguration(root.resolve(UUID.randomUUID().toString())));
            catalog = new JsonFileCatalog(storage);
            transfers = new TransferCoordinator(storage, catalog, 1L << 30);
            server = new TcpServer(InetAddress.getLoopbackAddress(), 0, max, timeout,
                    new RequestDispatcher(catalog, transfers));
            server.start();
        }

        TcpClient client() throws Exception {
            TcpClient c = new TcpClient(10000);
            c.connect("127.0.0.1", server.port());
            return c;
        }

        public void close() throws Exception {
            server.close();
            transfers.close();
            assertTrue(server.statistics().terminated());
            assertEquals(0, server.statistics().activeConnections());
        }
    }

    Path source(String folder) throws Exception {
        Path directory = Files.createDirectories(root.resolve(folder));
        Path p = directory.resolve("same-name.bin");
        byte[] b = new byte[1024 * 1024];
        new Random(folder.hashCode()).nextBytes(b);
        b[0] = 0;
        Files.write(p, b);
        return p;
    }

    UploadService uploads(TcpClient c) {
        return new UploadService(c, 1L << 30, new UploadCheckpointStore(root.resolve("client")));
    }

    static void gate(CyclicBarrier barrier) {
        try {
            barrier.await(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    void parallel(int n, java.util.function.IntConsumer action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int id = i;
                tasks.add(pool.submit(() -> action.accept(id)));
            }
            for (var task : tasks)
                task.get(45, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void oneClientUpload() throws Exception {
        uploadsTogether(1);
    }

    @Test
    void fourConcurrentUploads() throws Exception {
        uploadsTogether(4);
    }

    @Test
    void eightConcurrentUploadsWithSameNames() throws Exception {
        uploadsTogether(8);
    }

    void uploadsTogether(int n) throws Exception {
        try (Running r = new Running(32, 30000)) {
            List<Path> sources = new ArrayList<>();
            for (int i = 0; i < n; i++)
                sources.add(source("u" + i));
            CyclicBarrier barrier = new CyclicBarrier(n);
            Set<UUID> ids = ConcurrentHashMap.newKeySet();
            parallel(n, i -> {
                try (TcpClient c = r.client()) {
                    var result = uploads(c).uploadFile(sources.get(i), p -> {
                        if (p.bytesTransferred() == 0)
                            gate(barrier);
                    });
                    assertTrue(ids.add(result.file().fileId()));
                    assertEquals(FileHashes.sha256(sources.get(i)), result.file().sha256());
                    assertEquals(-1, Files.mismatch(sources.get(i), r.storage.completedPath(result.file().fileId())));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            assertEquals(n, r.catalog.listFiles().size());
            assertEquals(n, ids.size());
            assertEquals(n, r.server.statistics().largestWorkerPool());
            proof.add(Map.of("scenario", "upload", "simultaneousClientsAtReady", n, "completed", n, "sha256", "matched",
                    "largestPool", r.server.statistics().largestWorkerPool()));
        }
    }

    @Test
    void fourDownloadsAndMixedUploadsRunInParallel() throws Exception {
        try (Running r = new Running(32, 30000)) {
            Path source = source("seed");
            FileInfo file;
            try (TcpClient c = r.client()) {
                file = uploads(c).uploadFile(source, p -> {
                }).file();
            }
            CyclicBarrier barrier = new CyclicBarrier(8);
            parallel(8, i -> {
                try (TcpClient c = r.client()) {
                    if (i < 4) {
                        Path target = root.resolve("download" + i);
                        new DownloadService(c).downloadFile(file.fileId(), target, p -> {
                            if (p.bytesTransferred() == 0)
                                gate(barrier);
                        });
                        assertEquals(-1, Files.mismatch(source, target));
                        assertEquals(file.sha256(), FileHashes.sha256(target));
                    } else {
                        Path p = source("mixed" + i);
                        uploads(c).uploadFile(p, event -> {
                            if (event.bytesTransferred() == 0)
                                gate(barrier);
                        });
                    }
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            assertEquals(5, r.catalog.listFiles().size());
            proof.add(Map.of("scenario", "mixed", "uploadClients", 4, "downloadClients", 4, "sha256", "matched"));
        }
    }

    @Test
    void concurrentListsSeeOnlyVerifiedUniqueRecords() throws Exception {
        try (Running r = new Running(32, 30000)) {
            CyclicBarrier barrier = new CyclicBarrier(8);
            parallel(8, i -> {
                try (TcpClient c = r.client()) {
                    gate(barrier);
                    if (i < 4)
                        uploads(c).uploadFile(source("listed" + i), p -> {
                        });
                    else
                        for (int k = 0; k < 10; k++) {
                            var files = c.listFiles().files();
                            assertEquals(files.size(), files.stream().map(FileInfo::fileId).distinct().count());
                            for (var f : files) {
                                assertEquals(f.sha256(), FileHashes.sha256(r.storage.completedPath(f.fileId())));
                            }
                        }
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            assertEquals(4, r.catalog.listFiles().size());
        }
    }

    @Test
    void malformedOffsetAndDisconnectedPeerDoNotAffectHeldHealthyUploads() throws Exception {
        try (Running r = new Running(32, 30000)) {
            ExecutorService pool = Executors.newFixedThreadPool(4);
            CountDownLatch ready = new CountDownLatch(4), release = new CountDownLatch(1);
            List<Future<?>> good = new ArrayList<>();
            try {
                for (int i = 0; i < 4; i++) {
                    Path path = source("healthy" + i);
                    good.add(pool.submit(() -> {
                        try (TcpClient c = r.client()) {
                            uploads(c).uploadFile(path, p -> {
                                if (p.bytesTransferred() == 0) {
                                    ready.countDown();
                                    try {
                                        assertTrue(release.await(15, TimeUnit.SECONDS));
                                    } catch (InterruptedException e) {
                                        throw new AssertionError(e);
                                    }
                                }
                            });
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                    }));
                }
                assertTrue(ready.await(15, TimeUnit.SECONDS));
                try (Socket bad = new Socket("127.0.0.1", r.server.port())) {
                    bad.setSoTimeout(3000);
                    new DataOutputStream(bad.getOutputStream()).writeInt(-1);
                    assertEquals(-1, bad.getInputStream().read());
                }
                try (TcpClient bad = r.client()) {
                    bad.exchange(connection -> {
                        UUID id = UUID.randomUUID();
                        connection.send(ControlCodec.encode(MessageType.UPLOAD_INIT,
                                new UploadInit(1, id, "bad.bin", 100, "0".repeat(64))));
                        var transfer = ControlCodec.decode(connection.receive(id), TransferReady.class);
                        connection.send(new Frame(MessageType.FILE_CHUNK,
                                FileChunkCodec.encode(new FileChunk(transfer.transferId(), 1, new byte[] { 1 }))));
                        assertEquals(ErrorCode.OFFSET_MISMATCH,
                                assertThrows(ServerResponseException.class, () -> connection.receive(id)).code());
                        return null;
                    });
                }
                release.countDown();
                for (var f : good)
                    f.get(20, TimeUnit.SECONDS);
                assertEquals(4, r.catalog.listFiles().size());
                try (TcpClient c = r.client()) {
                    assertEquals(4, c.listFiles().files().size());
                }
            } finally {
                release.countDown();
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void resumeAndFreshUploadOverlap() throws Exception {
        try (Running r = new Running(32, 30000); TcpClient original = r.client()) {
            Path path = source("resume");
            AtomicBoolean once = new AtomicBoolean();
            var failure = assertThrows(TransferInterruptedException.class,
                    () -> uploads(original).uploadFile(path, p -> {
                        if (p.bytesTransferred() >= 262144 && once.compareAndSet(false, true))
                            try {
                                original.disconnect();
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                    }));
            assertTrue(r.transfers.sessions().awaitDetached(failure.transferId(), Duration.ofSeconds(5)));
            CyclicBarrier barrier = new CyclicBarrier(2);
            parallel(2, i -> {
                try (TcpClient c = r.client()) {
                    if (i == 0) {
                        var result = uploads(c).resumeUpload(failure.transferId(), path, p -> {
                            if (p.bytesTransferred() == failure.nextOffset())
                                gate(barrier);
                        });
                        assertEquals(Files.size(path) - failure.nextOffset(), result.payloadBytesThisAttempt());
                    } else
                        uploads(c).uploadFile(source("fresh"), p -> {
                            if (p.bytesTransferred() == 0)
                                gate(barrier);
                        });
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            assertEquals(2, r.catalog.listFiles().size());
        }
    }

    @Test
    void overloadReturnsCorrelatedBusyAndRecovers() throws Exception {
        try (Running r = new Running(1, 30000); TcpClient first = r.client()) {
            first.listFiles();
            try (TcpClient extra = r.client()) {
                var error = assertThrows(ServerResponseException.class, extra::listFiles);
                assertEquals(ErrorCode.SERVER_BUSY, error.code());
            }
            assertEquals(1, first.listFiles().files().size() + 1);
            assertEquals(1, r.server.statistics().largestWorkerPool());
            assertTrue(r.server.statistics().rejectedConnections() > 0);
            // A silent overload peer is closed by the absolute admission deadline.
            try (Socket silent = new Socket("127.0.0.1", r.server.port())) {
                silent.setSoTimeout(2000);
                assertEquals(-1, silent.getInputStream().read());
            }
        }
    }

    @Test
    void timeoutAndShutdownReleaseSocketsAndKeepPart() throws Exception {
        try (Running r = new Running(4, 300); Socket idle = new Socket("127.0.0.1", r.server.port())) {
            idle.setSoTimeout(2000);
            assertEquals(-1, idle.getInputStream().read());
            try (TcpClient c = r.client()) {
                c.listFiles();
            }
        }
        try (Running r = new Running(4, 30000); TcpClient c = r.client()) {
            var ready = c.exchange(conn -> {
                UUID id = UUID.randomUUID();
                conn.send(ControlCodec.encode(MessageType.UPLOAD_INIT,
                        new UploadInit(1, id, "partial.bin", 100, "0".repeat(64))));
                return ControlCodec.decode(conn.receive(id), TransferReady.class);
            });
            r.server.close();
            assertTrue(r.server.statistics().terminated());
            assertTrue(Files.exists(r.storage.partPath(ready.transferId())));
            assertTrue(r.catalog.listFiles().isEmpty());
            assertEquals(TransferState.INTERRUPTED, r.transfers.sessions().snapshot(ready.transferId()).state());
            try (ServerSocket rebound = new ServerSocket()) {
                rebound.setReuseAddress(true);
                rebound.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), r.server.port()));
            }
        }
    }
}
