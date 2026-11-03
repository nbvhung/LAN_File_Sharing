package vn.edu.lanfileshare.client.ui;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.client.network.TcpClient;
import vn.edu.lanfileshare.client.transfer.*;
import vn.edu.lanfileshare.common.io.FileHashes;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.FrameCodec;
import vn.edu.lanfileshare.server.network.*;
import vn.edu.lanfileshare.server.storage.*;
import vn.edu.lanfileshare.server.transfer.*;

/** Real backend + real loopback TCP; only the view is headless, not the networking. */
class ClientControllerIntegrationTest {
    @TempDir Path root;
    private final List<ClientController> controllers = new ArrayList<>();
    static final class View implements ClientView {
        final BlockingQueue<UiSnapshot> events = new LinkedBlockingQueue<>();
        final List<String> notices = new CopyOnWriteArrayList<>();
        volatile UiSnapshot latest; volatile boolean closed;
        public void render(UiSnapshot value) { assertTrue(SwingUtilities.isEventDispatchThread()); latest = value; events.add(value); }
        public void log(String message) { assertTrue(SwingUtilities.isEventDispatchThread()); }
        public void notice(String title, String message, boolean error) { assertTrue(SwingUtilities.isEventDispatchThread()); notices.add(title + ": " + message); }
        public void closed() { assertTrue(SwingUtilities.isEventDispatchThread()); closed = true; }
        UiSnapshot await(java.util.function.Predicate<UiSnapshot> condition) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (System.nanoTime() < deadline) {
                UiSnapshot value = events.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (value != null && condition.test(value)) return value;
            }
            fail("Timed out; latest UI state: " + latest); return null;
        }
        void clear() { events.clear(); }
    }
    record Running(TcpServer server, TransferCoordinator transfers, FileStorageService storage) implements AutoCloseable {
        public void close() throws Exception { server.close(); transfers.close(); }
    }
    private Running start() throws Exception {
        FileStorageService storage = new FileStorageService(new StorageConfiguration(root.resolve("server")));
        JsonFileCatalog catalog = new JsonFileCatalog(storage);
        TransferCoordinator transfers = new TransferCoordinator(storage, catalog, 1L << 30);
        TcpServer server = new TcpServer(InetAddress.getLoopbackAddress(), 0, 8, 10000, new RequestDispatcher(catalog, transfers));
        server.start(); return new Running(server, transfers, storage);
    }
    static void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    private ClientController controller(ClientBackend backend, View view) throws Exception {
        AtomicReference<ClientController> result = new AtomicReference<>();
        edt(() -> result.set(new ClientController(backend, view))); controllers.add(result.get()); return result.get();
    }
    @AfterEach void closeControllers() throws Exception {
        for (ClientController controller : controllers) { edt(controller::close); controller.termination().get(10, TimeUnit.SECONDS); }
    }
    private Path source(int megabytes) throws IOException {
        Path path = root.resolve("source-" + megabytes + ".bin");
        byte[] block = new byte[65536]; new Random(45).nextBytes(block); block[0] = 0;
        try (OutputStream out = Files.newOutputStream(path)) { for (int i = 0; i < megabytes * 16; i++) out.write(block); }
        return path;
    }
    private void connect(ClientController controller, View view, int port) throws Exception {
        view.clear(); edt(() -> controller.connect("127.0.0.1", Integer.toString(port)));
        view.await(s -> !s.busy() && s.state() == UiState.CONNECTED);
    }
    @Test void uploadListDownloadThroughControllerHasRealBytesAndEdtOnlyViews() throws Exception {
        try (Running running = start()) {
            View view = new View(); ClientController c = controller(new ServiceClientBackend(new TcpClient(10000), root.resolve("client")), view);
            connect(c, view, running.server.port()); assertTrue(view.latest.files().isEmpty());
            edt(() -> c.download(null, root.resolve("unused.bin"))); assertTrue(view.notices.getLast().contains("Select a server file"));
            Path source = source(1); view.clear(); edt(() -> c.upload(source));
            UiSnapshot uploaded = view.await(s -> !s.busy() && s.state() == UiState.COMPLETED);
            assertEquals(1, uploaded.files().size()); assertEquals(100, uploaded.transfers().getFirst().percentage());
            FileInfo file = uploaded.files().getFirst(); Path target = root.resolve("received.bin");
            view.clear(); edt(() -> c.download(file, target));
            UiSnapshot downloaded = view.await(s -> !s.busy() && s.transfers().size() == 2 && s.state() == UiState.COMPLETED);
            assertEquals(-1, Files.mismatch(source, target)); assertEquals(FileHashes.sha256(source), FileHashes.sha256(target));
            assertTrue(downloaded.transfers().stream().allMatch(t -> t.status() == UiState.COMPLETED));
            view.clear(); edt(c::disconnect); view.await(s -> !s.busy() && s.state() == UiState.DISCONNECTED);
            assertEquals(2, view.latest.transfers().size()); assertTrue(view.latest.files().isEmpty());
        }
    }
    /** Decorator retains real services; the gate controls exactly which callback loses the socket. */
    static final class GateBackend implements ClientBackend {
        final ClientBackend delegate; final AtomicReference<Direction> cut = new AtomicReference<>();
        final AtomicReference<ClientController> controller = new AtomicReference<>();
        final AtomicReference<CountDownLatch> cutDone = new AtomicReference<>(new CountDownLatch(1));
        final CountDownLatch largeStarted = new CountDownLatch(1), largeRelease = new CountDownLatch(1);
        boolean gateLarge;
        GateBackend(ClientBackend delegate) { this.delegate = delegate; }
        void checkWorker() { assertFalse(SwingUtilities.isEventDispatchThread(), "Backend I/O ran on EDT"); }
        void observe(Direction direction, TransferProgress p, ProgressListener<TransferProgress> listener) {
            checkWorker(); listener.onProgress(p);
            if (gateLarge && p.bytesTransferred() == 0) {
                largeStarted.countDown();
                try { assertTrue(largeRelease.await(10, TimeUnit.SECONDS)); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
            }
            if (p.bytesTransferred() >= 3L * 1024 * 1024 && p.percentage() < 100 && cut.compareAndSet(direction, null)) {
                try { edt(controller.get()::disconnect); assertTrue(cutDone.get().await(5, TimeUnit.SECONDS)); }
                catch (Exception ex) { throw new AssertionError(ex); }
            }
        }
        void arm(Direction direction) { cutDone.set(new CountDownLatch(1)); cut.set(direction); }
        public void connect(Endpoint e) throws IOException { checkWorker(); delegate.connect(e); }
        public void disconnect() throws IOException { checkWorker(); delegate.disconnect(); }
        public boolean connected() { checkWorker(); return delegate.connected(); }
        public List<FileInfo> listFiles() throws IOException { checkWorker(); return delegate.listFiles(); }
        public Outcome upload(Path p, ProgressListener<TransferProgress> l) throws IOException { checkWorker(); return delegate.upload(p, event -> observe(Direction.UPLOAD, event, l)); }
        public Outcome download(UUID id, Path p, ProgressListener<TransferProgress> l) throws IOException { checkWorker(); return delegate.download(id, p, event -> observe(Direction.DOWNLOAD, event, l)); }
        public Outcome resume(TransferItem t, ProgressListener<TransferProgress> l) throws IOException { checkWorker(); return delegate.resume(t, event -> observe(t.direction(), event, l)); }
        public void interrupt() throws IOException { checkWorker(); delegate.interrupt(); cutDone.get().countDown(); }
        public void close() throws IOException { checkWorker(); delegate.close(); }
    }
    @Test void interruptedUploadAndDownloadResumeSameIdsWithoutReplayingWholeFile() throws Exception {
        try (Running running = start()) {
            TcpClient client = new TcpClient(10000);
            GateBackend backend = new GateBackend(new ServiceClientBackend(client, root.resolve("client")));
            View view = new View(); ClientController c = controller(backend, view); backend.controller.set(c);
            connect(c, view, running.server.port()); Path source = source(10);
            backend.arm(Direction.UPLOAD); view.clear(); edt(() -> c.upload(source));
            TransferItem upload = view.await(s -> !s.busy() && s.state() == UiState.INTERRUPTED).transfers().getFirst();
            assertEquals(3L * 1024 * 1024, upload.bytes()); assertTrue(upload.resumable());
            assertTrue(running.transfers.sessions().awaitDetached(upload.transferId(), Duration.ofSeconds(5)));
            long sentBefore = client.metrics().sentDataBytes(); assertEquals(upload.bytes(), sentBefore);
            // Hold the real server lease on another socket. Exhausted SERVER_BUSY retries
            // must keep the old row resumable rather than discarding its checkpoint.
            connect(c, view, running.server.port());
            var saved = running.transfers.sessions().snapshot(upload.transferId());
            try (TcpClient owner = new TcpClient(10000)) {
                owner.connect("127.0.0.1", running.server.port());
                owner.exchange(connection -> {
                    UUID request = UUID.randomUUID();
                    connection.send(vn.edu.lanfileshare.common.protocol.ControlCodec.encode(
                        vn.edu.lanfileshare.common.protocol.MessageType.RESUME_QUERY,
                        new ResumeQuery(1, request, saved.transferId(), Direction.UPLOAD, saved.fileId(), saved.size(), saved.sha256())));
                    assertEquals(vn.edu.lanfileshare.common.protocol.MessageType.TRANSFER_READY, connection.receive(request).type());
                    return null;
                });
                view.clear(); edt(() -> c.resume(upload.rowId()));
                UiSnapshot busyFailure = view.await(s -> !s.busy() && s.state() == UiState.INTERRUPTED && s.message().startsWith("Resume failed"));
                assertTrue(busyFailure.transfers().getFirst().resumable());
                assertEquals(sentBefore, client.metrics().sentDataBytes());
            }
            assertTrue(running.transfers.sessions().awaitDetached(upload.transferId(), Duration.ofSeconds(5)));
            connect(c, view, running.server.port()); view.clear(); edt(() -> c.resume(upload.rowId()));
            UiSnapshot uploaded = view.await(s -> !s.busy() && s.state() == UiState.COMPLETED);
            assertEquals(upload.transferId(), uploaded.transfers().getFirst().transferId());
            assertEquals(Files.size(source), client.metrics().sentDataBytes());
            FileInfo file = uploaded.files().getFirst(); Path target = root.resolve("resumed.bin");
            backend.arm(Direction.DOWNLOAD); view.clear(); edt(() -> c.download(file, target));
            TransferItem download = view.await(s -> !s.busy() && s.state() == UiState.INTERRUPTED).transfers().getLast();
            assertTrue(download.resumable()); assertFalse(Files.exists(target)); assertEquals(3L * 1024 * 1024, download.bytes());
            assertTrue(running.transfers.sessions().awaitDetached(download.transferId(), Duration.ofSeconds(5)));
            connect(c, view, running.server.port()); view.clear(); edt(() -> c.resume(download.rowId()));
            UiSnapshot completed = view.await(s -> !s.busy() && s.state() == UiState.COMPLETED);
            assertEquals(download.transferId(), completed.transfers().getLast().transferId());
            assertEquals(Files.size(source), client.metrics().receivedDataBytes());
            assertEquals(-1, Files.mismatch(source, target)); assertEquals(FileHashes.sha256(source), FileHashes.sha256(target));
            System.out.println("SWING_CONTROLLER_RESUME uploadBefore=" + sentBefore + " uploadAfter=" + (client.metrics().sentDataBytes() - sentBefore)
                + " downloadBefore=" + download.bytes() + " downloadAfter=" + (client.metrics().receivedDataBytes() - download.bytes()));
        }
    }
    @Test void hundredMiBTransferDoesNotBlockEdtOrPermitSecondOperation() throws Exception {
        try (Running running = start()) {
            GateBackend backend = new GateBackend(new ServiceClientBackend(new TcpClient(10000), root.resolve("client"))); backend.gateLarge = true;
            View view = new View(); ClientController c = controller(backend, view); connect(c, view, running.server.port());
            Path source = source(100); view.clear(); edt(() -> c.upload(source));
            assertTrue(backend.largeStarted.await(30, TimeUnit.SECONDS));
            CountDownLatch heartbeat = new CountDownLatch(1); SwingUtilities.invokeLater(heartbeat::countDown);
            assertTrue(heartbeat.await(2, TimeUnit.SECONDS), "EDT failed to process heartbeat while transfer worker was blocked");
            edt(() -> { c.refresh(); c.upload(source); }); assertEquals(1, view.latest.transfers().size());
            backend.largeRelease.countDown();
            UiSnapshot uploaded = view.await(s -> !s.busy() && s.state() == UiState.COMPLETED);
            FileInfo file = uploaded.files().getFirst(); assertEquals(100L * 1024 * 1024, file.size());
            assertEquals(-1, Files.mismatch(source, running.storage.completedPath(file.fileId())));
            assertEquals(FileHashes.sha256(source), file.sha256());
            System.out.println("SWING_CONTROLLER_100MIB_PASS sha256=" + file.sha256() + " edtHeartbeat=true");
        }
    }
    @Test void invalidEndpointOfflineRefreshAndRefusedPortAreControlled() throws Exception {
        View view = new View(); ClientController c = controller(new ServiceClientBackend(new TcpClient(1000), root.resolve("client")), view);
        edt(c::refresh); assertTrue(view.notices.getLast().contains("Connect to a server"));
        edt(() -> c.connect("127.0.0.1", "invalid")); assertFalse(view.latest.busy()); assertTrue(view.notices.getLast().contains("number"));
        edt(() -> c.connect("", "49000")); assertTrue(view.notices.getLast().contains("server IP"));
        int port; try (ServerSocket reserved = new ServerSocket(0)) { port = reserved.getLocalPort(); }
        view.clear(); edt(() -> c.connect("127.0.0.1", Integer.toString(port)));
        UiSnapshot failed = view.await(s -> !s.busy() && s.state() == UiState.FAILED);
        assertFalse(failed.connected()); assertTrue(failed.message().contains("Cannot connect"));
    }
    @Test void closeUnblocksRealSilentServerWithoutWaitingForReadTimeout() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CountDownLatch requestRead = new CountDownLatch(1), peerClosed = new CountDownLatch(1);
            ExecutorService peer = Executors.newSingleThreadExecutor();
            Future<?> future = peer.submit(() -> {
                try (Socket socket = listener.accept()) {
                    FrameCodec.read(socket.getInputStream()); requestRead.countDown();
                    assertEquals(-1, socket.getInputStream().read()); peerClosed.countDown();
                } catch (IOException ex) { throw new UncheckedIOException(ex); }
            });
            try {
                View view = new View(); ClientController c = controller(new ServiceClientBackend(new TcpClient(30000), root.resolve("client")), view);
                edt(() -> c.connect("127.0.0.1", Integer.toString(listener.getLocalPort())));
                assertTrue(requestRead.await(5, TimeUnit.SECONDS)); long started = System.nanoTime();
                edt(c::close); assertTrue(view.closed); c.termination().get(3, TimeUnit.SECONDS);
                assertTrue(peerClosed.await(3, TimeUnit.SECONDS)); future.get(3, TimeUnit.SECONDS);
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3));
            } finally { peer.shutdownNow(); }
        }
    }
    @Test void destinationConflictIsFailedAndOldFileIsUntouched() throws Exception {
        try (Running running = start()) {
            View view = new View(); ClientController c = controller(new ServiceClientBackend(new TcpClient(10000), root.resolve("client")), view);
            connect(c, view, running.server.port()); Path source = source(1); edt(() -> c.upload(source));
            FileInfo file = view.await(s -> !s.busy() && s.state() == UiState.COMPLETED).files().getFirst();
            Path target = root.resolve("existing.bin"); Files.writeString(target, "keep me");
            view.clear(); edt(() -> c.download(file, target));
            UiSnapshot failure = view.await(s -> !s.busy() && s.state() == UiState.FAILED);
            assertEquals("keep me", Files.readString(target)); assertEquals(UiState.FAILED, failure.transfers().getLast().status());
            assertFalse(failure.transfers().getLast().resumable());
        }
    }
}
