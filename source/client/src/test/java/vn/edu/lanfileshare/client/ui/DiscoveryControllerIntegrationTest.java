package vn.edu.lanfileshare.client.ui;

import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.client.discovery.*;
import vn.edu.lanfileshare.common.io.FileHashes;
import vn.edu.lanfileshare.server.discovery.*;
import vn.edu.lanfileshare.server.network.*;
import vn.edu.lanfileshare.server.storage.*;
import vn.edu.lanfileshare.server.transfer.TransferCoordinator;

/** Real UDP/TCP behind the controller. The test view checks every delivery occurs on the EDT. */
class DiscoveryControllerIntegrationTest {
    @TempDir Path root;
    private final List<ClientController> controllers = new ArrayList<>();

    static class View implements ClientView {
        final BlockingQueue<UiSnapshot> events = new LinkedBlockingQueue<>();
        final List<String> notices = new CopyOnWriteArrayList<>();
        volatile UiSnapshot latest;
        volatile boolean closed;

        public void render(UiSnapshot value) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            latest = value;
            events.add(value);
        }

        public void log(String message) { assertTrue(SwingUtilities.isEventDispatchThread()); }
        public void notice(String title, String message, boolean error) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            notices.add(title + ": " + message);
        }
        public void closed() { assertTrue(SwingUtilities.isEventDispatchThread()); closed = true; }

        UiSnapshot await(java.util.function.Predicate<UiSnapshot> condition) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline) {
                UiSnapshot value = events.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (value != null && condition.test(value)) return value;
            }
            throw new AssertionError("Timed out; latest state: " + latest);
        }
    }

    final class Running implements AutoCloseable {
        final TransferCoordinator transfers;
        final JsonFileCatalog catalog;
        final TcpServer tcp;
        final DiscoveryServer udp;

        Running() throws Exception {
            FileStorageService storage = new FileStorageService(
                    new StorageConfiguration(root.resolve(UUID.randomUUID().toString())));
            catalog = new JsonFileCatalog(storage);
            transfers = new TransferCoordinator(storage, catalog, 1L << 30);
            tcp = new TcpServer(InetAddress.getLoopbackAddress(), 0, 8, 10000,
                    new RequestDispatcher(catalog, transfers));
            tcp.start();
            udp = new DiscoveryServer(new DiscoveryConfiguration(InetAddress.getLoopbackAddress(), 0, tcp.port()));
            udp.start();
        }

        DiscoveryTarget target() { return DiscoveryTarget.unicast(InetAddress.getLoopbackAddress(), udp.port()); }

        public void close() throws Exception {
            udp.close(); tcp.close(); transfers.close();
            assertFalse(udp.isRunning()); assertTrue(tcp.statistics().terminated());
        }
    }

    static void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }

    ClientController create(ServerDiscovery discovery, View view) throws Exception {
        AtomicReference<ClientController> result = new AtomicReference<>();
        edt(() -> result.set(new ClientController(new ServiceClientBackend(root.resolve("client")), discovery, view)));
        controllers.add(result.get());
        return result.get();
    }

    ServiceServerDiscovery service(ServiceServerDiscovery.Targets targets) {
        return new ServiceServerDiscovery(() -> new DiscoveryClient(new DiscoveryOptions(80, 2)), targets);
    }

    @AfterEach
    void closeControllers() throws Exception {
        for (var controller : controllers) {
            edt(controller::close);
            controller.termination().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void multipleServersRequireSelectionThenRealUploadDownloadWorks() throws Exception {
        try (Running first = new Running(); Running second = new Running()) {
            AtomicInteger requestedPort = new AtomicInteger();
            View view = new View();
            ClientController c = create(service(port -> {
                assertFalse(SwingUtilities.isEventDispatchThread()); requestedPort.set(port);
                return List.of(first.target(), second.target());
            }), view);
            edt(() -> c.discover("49011"));
            assertEquals(UiState.DISCOVERING, view.await(s -> s.state() == UiState.DISCOVERING).state());
            UiSnapshot found = view.await(s -> !s.busy() && s.discoveredServers().size() == 2);
            assertEquals(49011, requestedPort.get());
            assertNull(found.selectedServer()); assertFalse(found.connected()); assertNull(found.endpoint());
            assertEquals(0, first.tcp.statistics().activeConnections());
            assertEquals(0, second.tcp.statistics().activeConnections());
            DiscoveredServer selected = found.discoveredServers().stream()
                    .filter(server -> server.tcpPort() == second.tcp.port()).findFirst().orElseThrow();
            edt(() -> c.selectServer(selected));
            assertEquals(selected, view.latest.selectedServer()); assertFalse(view.latest.connected());
            edt(() -> c.connect(selected.host(), Integer.toString(selected.tcpPort())));
            view.await(s -> s.connected() && !s.busy());
            edt(() -> c.discover("49011")); assertEquals(UiState.CONNECTED, view.latest.state());
            byte[] bytes = new byte[1024 * 1024]; new Random(314).nextBytes(bytes); bytes[0] = 0;
            Path source = Files.write(root.resolve("source.bin"), bytes);
            view.events.clear(); edt(() -> c.upload(source));
            UiSnapshot uploaded = view.await(s -> !s.busy() && s.state() == UiState.COMPLETED);
            assertEquals(1, second.catalog.listFiles().size()); assertTrue(first.catalog.listFiles().isEmpty());
            Path destination = root.resolve("received.bin");
            view.events.clear(); edt(() -> c.download(uploaded.files().getFirst(), destination));
            view.await(s -> !s.busy() && s.transfers().size() == 2 && s.state() == UiState.COMPLETED);
            assertEquals(-1, Files.mismatch(source, destination));
            assertEquals(FileHashes.sha256(source), FileHashes.sha256(destination));
        }
    }

    @Test
    void noRepliesAndInvalidUdpPortKeepManualTcpFallback() throws Exception {
        try (Running running = new Running(); DatagramSocket silent = new DatagramSocket(0)) {
            View view = new View();
            ClientController c = create(service(port -> List.of(DiscoveryTarget.unicast(
                    InetAddress.getLoopbackAddress(), silent.getLocalPort()))), view);
            edt(() -> c.discover("not-a-port")); assertFalse(view.latest.busy());
            assertTrue(view.notices.getLast().contains("UDP port"));
            view.events.clear(); edt(() -> c.discover("49001"));
            UiSnapshot empty = view.await(s -> !s.busy() && s.state() == UiState.DISCONNECTED);
            assertTrue(empty.discoveredServers().isEmpty()); assertTrue(empty.message().contains("manually"));
            edt(() -> c.selectServer(new DiscoveredServer(InetAddress.getLoopbackAddress(), running.tcp.port())));
            assertNull(view.latest.selectedServer()); assertTrue(view.notices.getLast().contains("current discovery"));
            edt(() -> c.connect("127.0.0.1", Integer.toString(running.tcp.port())));
            assertTrue(view.await(s -> !s.busy() && s.connected()).files().isEmpty());
        }
    }

    @Test
    void failurePreservesFallbackAndSearchCanBeRetried() throws Exception {
        try (Running running = new Running()) {
            AtomicBoolean first = new AtomicBoolean(true);
            View view = new View();
            ClientController c = create(service(port -> {
                if (first.getAndSet(false)) throw new IOException("No interface available");
                return List.of(running.target());
            }), view);
            edt(() -> c.discover("49001"));
            UiSnapshot failed = view.await(s -> !s.busy() && s.state() == UiState.FAILED);
            assertTrue(failed.message().contains("manually")); assertFalse(failed.connected());
            edt(() -> c.connect("127.0.0.1", Integer.toString(running.tcp.port())));
            view.await(s -> !s.busy() && s.connected());
            edt(c::disconnect); view.await(s -> !s.busy() && s.state() == UiState.DISCONNECTED);
            view.events.clear(); edt(() -> c.discover("49001"));
            assertEquals(1, view.await(s -> !s.busy() && !s.discoveredServers().isEmpty()).discoveredServers().size());
        }
    }

    @Test
    void discoveryWorkerLeavesEdtResponsiveAndRejectsOverlappingCommands() throws Exception {
        try (Running running = new Running()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            View view = new View();
            ClientController c = create(service(port -> {
                assertFalse(SwingUtilities.isEventDispatchThread()); entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test gate timeout"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
                return List.of(running.target());
            }), view);
            try {
                edt(() -> c.discover("49001")); assertTrue(entered.await(3, TimeUnit.SECONDS));
                CountDownLatch heartbeat = new CountDownLatch(1); SwingUtilities.invokeLater(heartbeat::countDown);
                assertTrue(heartbeat.await(1, TimeUnit.SECONDS));
                edt(() -> { c.connect("127.0.0.1", "49000"); c.upload(root.resolve("unused")); c.refresh(); });
                assertEquals(UiState.DISCOVERING, view.latest.state()); assertTrue(view.latest.transfers().isEmpty());
                assertEquals(0, running.tcp.statistics().activeConnections());
            } finally { release.countDown(); }
            view.await(s -> !s.busy() && s.discoveredServers().size() == 1);
        }
    }

    @Test
    void cancelClosesRealUdpAndAllowsAnotherSearch() throws Exception {
        try (Running running = new Running(); DatagramSocket silent = new DatagramSocket(0)) {
            silent.setSoTimeout(3000);
            AtomicReference<DiscoveryTarget> target = new AtomicReference<>(DiscoveryTarget.unicast(
                    InetAddress.getLoopbackAddress(), silent.getLocalPort()));
            View view = new View(); ClientController c = create(service(port -> List.of(target.get())), view);
            edt(() -> c.discover("49001"));
            silent.receive(new DatagramPacket(new byte[129], 129));
            edt(() -> { c.cancelDiscovery(); c.cancelDiscovery(); });
            UiSnapshot cancelled = view.await(s -> !s.busy() && s.message().startsWith("Discovery cancelled"));
            assertTrue(cancelled.discoveredServers().isEmpty()); assertTrue(view.notices.isEmpty());
            target.set(running.target()); view.events.clear(); edt(() -> c.discover("49001"));
            view.await(s -> !s.busy() && s.discoveredServers().size() == 1);
        }
    }

    @Test
    void windowCloseCancelsPendingUdpAndIgnoresLateResults() throws Exception {
        try (DatagramSocket silent = new DatagramSocket(0)) {
            silent.setSoTimeout(3000); View view = new View();
            ClientController c = create(new ServiceServerDiscovery(DiscoveryClient::new,
                    port -> List.of(DiscoveryTarget.unicast(InetAddress.getLoopbackAddress(), silent.getLocalPort()))), view);
            edt(() -> c.discover("49001")); silent.receive(new DatagramPacket(new byte[129], 129));
            edt(c::close); c.termination().get(2, TimeUnit.SECONDS); edt(() -> { });
            assertTrue(view.closed); assertTrue(view.latest.closing());
            assertTrue(view.latest.discoveredServers().isEmpty()); assertTrue(view.notices.isEmpty());
        }
    }
}
