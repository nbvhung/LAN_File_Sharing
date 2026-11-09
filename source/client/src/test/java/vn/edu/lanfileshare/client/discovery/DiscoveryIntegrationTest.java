package vn.edu.lanfileshare.client.discovery;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.client.network.TcpClient;
import vn.edu.lanfileshare.client.transfer.*;
import vn.edu.lanfileshare.common.io.FileHashes;
import vn.edu.lanfileshare.common.protocol.DiscoveryProtocol;
import vn.edu.lanfileshare.server.discovery.*;
import vn.edu.lanfileshare.server.network.*;
import vn.edu.lanfileshare.server.storage.*;
import vn.edu.lanfileshare.server.transfer.TransferCoordinator;

/** Every discovery reply/request in this suite crosses a real UDP socket. */
class DiscoveryIntegrationTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    @TempDir Path root;

    @BeforeAll
    static void warmEntropy() {
        new SecureRandom().nextBytes(new byte[16]);
        UUID.randomUUID();
    }

    final class Running implements AutoCloseable {
        final TransferCoordinator transfers;
        final TcpServer tcp;
        final DiscoveryServer udp;

        Running() throws Exception {
            var storage = new FileStorageService(new StorageConfiguration(root.resolve(UUID.randomUUID().toString())));
            var catalog = new JsonFileCatalog(storage);
            transfers = new TransferCoordinator(storage, catalog, 1L << 30);
            tcp = new TcpServer(LOOPBACK, 0, 8, 10000, new RequestDispatcher(catalog, transfers));
            tcp.start();
            udp = new DiscoveryServer(new DiscoveryConfiguration(LOOPBACK, 0, tcp.port()));
            udp.start();
        }

        DiscoveryTarget target() {
            return DiscoveryTarget.unicast(LOOPBACK, udp.port());
        }

        public void close() throws Exception {
            udp.close();
            tcp.close();
            transfers.close();
            assertFalse(udp.isRunning());
            assertTrue(tcp.statistics().terminated());
        }
    }

    private DiscoveryClient client() {
        return new DiscoveryClient(new DiscoveryOptions(100, 3));
    }

    @Test
    void discoversThenExplicitlyConnectsTcpAndListsFiles() throws Exception {
        try (Running running = new Running(); DiscoveryClient discovery = client()) {
            var servers = discovery.discover(List.of(running.target()));
            assertEquals(List.of(new DiscoveredServer(LOOPBACK, running.tcp.port())), servers);
            assertEquals(0, running.tcp.statistics().activeConnections(), "Discovery must never auto-connect TCP");
            try (TcpClient tcp = new TcpClient(3000)) {
                tcp.connect(servers.getFirst().host(), servers.getFirst().tcpPort());
                assertTrue(tcp.listFiles().files().isEmpty());
            }
            assertThrows(UnsupportedOperationException.class, () -> servers.clear());
        }
    }

    @Test
    void multipleServersAndMultipleSourceInterfacesAreDeduplicated() throws Exception {
        try (Running first = new Running(); Running second = new Running(); DiscoveryClient discovery = client()) {
            List<DiscoveryTarget> targets = List.of(first.target(), second.target(), first.target(),
                    new DiscoveryTarget(LOOPBACK, first.target().destination()));
            var found = discovery.discover(targets);
            assertEquals(Set.of(new DiscoveredServer(LOOPBACK, first.tcp.port()),
                    new DiscoveredServer(LOOPBACK, second.tcp.port())), new HashSet<>(found));
            assertEquals(2, found.size());
            assertEquals(0, first.tcp.statistics().activeConnections());
            assertEquals(0, second.tcp.statistics().activeConnections());
        }
    }

    private static void reply(DatagramSocket peer, DatagramPacket request, byte[] data) throws IOException {
        peer.send(new DatagramPacket(data, data.length, request.getSocketAddress()));
    }

    @Test
    void malformedWrongVersionNonceOversizeAndClaimedIpAreIgnored() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (DatagramSocket peer = new DatagramSocket(0, LOOPBACK); DiscoveryClient discovery = client()) {
            peer.setSoTimeout(3000);
            Set<String> nonces = ConcurrentHashMap.newKeySet();
            Future<?> responder = executor.submit(() -> {
                try {
                    for (int attempt = 0; attempt < 3; attempt++) {
                        DatagramPacket query = new DatagramPacket(new byte[129], 129);
                        peer.receive(query);
                        String nonce = DiscoveryProtocol.parseRequest(query.getData(), 0, query.getLength()).orElseThrow();
                        assertTrue(nonces.add(nonce), "Each attempt uses a fresh nonce");
                        for (byte[] invalid : new byte[][] { "x".getBytes(StandardCharsets.US_ASCII),
                                ("LANFS_HERE/2 " + nonce + " 49000").getBytes(StandardCharsets.US_ASCII),
                                DiscoveryProtocol.response("f".repeat(32), 49000),
                                ("LANFS_HERE/1 " + nonce + " 0").getBytes(StandardCharsets.US_ASCII),
                                ("LANFS_HERE/1 " + nonce + " 65536").getBytes(StandardCharsets.US_ASCII),
                                ("LANFS_HERE/1 " + nonce + " 49000 203.0.113.1").getBytes(StandardCharsets.US_ASCII),
                                Arrays.copyOf(DiscoveryProtocol.response(nonce, 49100), 4096),
                                new byte[] { (byte) 0xff } }) {
                            reply(peer, query, invalid);
                        }
                        reply(peer, query, DiscoveryProtocol.response(nonce, 49100));
                        reply(peer, query, DiscoveryProtocol.response(nonce, 49100));
                    }
                } catch (IOException ex) {
                    throw new AssertionError(ex);
                }
            });
            var found = discovery.discover(List.of(DiscoveryTarget.unicast(LOOPBACK, peer.getLocalPort())));
            responder.get(4, TimeUnit.SECONDS);
            assertEquals(3, nonces.size());
            assertEquals(List.of(new DiscoveredServer(LOOPBACK, 49100)), found);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void wrongNonceOnlyRepliesStillRespectRetryAndTimeoutLimits() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicInteger requests = new AtomicInteger();
        try (DatagramSocket peer = new DatagramSocket(0, LOOPBACK);
                DiscoveryClient discovery = new DiscoveryClient(new DiscoveryOptions(60, 3))) {
            peer.setSoTimeout(2000);
            Future<?> responder = executor.submit(() -> {
                try {
                    for (int i = 0; i < 3; i++) {
                        DatagramPacket query = new DatagramPacket(new byte[129], 129);
                        peer.receive(query);
                        requests.incrementAndGet();
                        String nonce = DiscoveryProtocol.parseRequest(query.getData(), 0, query.getLength()).orElseThrow();
                        String wrong = (nonce.charAt(0) == 'a' ? "b" : "a") + nonce.substring(1);
                        reply(peer, query, DiscoveryProtocol.response(wrong, 49100));
                    }
                } catch (IOException ex) {
                    throw new AssertionError(ex);
                }
            });
            long started = System.nanoTime();
            assertTrue(discovery.discover(List.of(DiscoveryTarget.unicast(LOOPBACK, peer.getLocalPort()))).isEmpty());
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
            responder.get(3, TimeUnit.SECONDS);
            assertEquals(3, requests.get());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void malformedPacketFloodCannotExtendAbsoluteDeadline() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (DatagramSocket peer = new DatagramSocket(0, LOOPBACK);
                DiscoveryClient discovery = new DiscoveryClient(new DiscoveryOptions(100, 1))) {
            peer.setSoTimeout(2000);
            Future<?> sender = executor.submit(() -> {
                try {
                    DatagramPacket query = new DatagramPacket(new byte[129], 129);
                    peer.receive(query);
                    long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
                    while (System.nanoTime() < until) {
                        reply(peer, query, new byte[] { 0 });
                    }
                } catch (IOException ex) {
                    throw new AssertionError(ex);
                }
            });
            long started = System.nanoTime();
            assertTrue(discovery.discover(List.of(DiscoveryTarget.unicast(LOOPBACK, peer.getLocalPort()))).isEmpty());
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
            sender.get(3, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void absentServerAndUdpFailureDoNotDisableManualTcpFallback() throws Exception {
        int absentPort;
        try (DatagramSocket reserved = new DatagramSocket(0, LOOPBACK)) {
            absentPort = reserved.getLocalPort();
        }
        try (Running running = new Running();
                DiscoveryClient discovery = new DiscoveryClient(new DiscoveryOptions(50, 2));
                TcpClient tcp = new TcpClient(3000)) {
            assertTrue(discovery.discover(List.of(DiscoveryTarget.unicast(LOOPBACK, absentPort))).isEmpty());
            try (DatagramSocket occupied = new DatagramSocket(0, LOOPBACK)) {
                assertThrows(SocketException.class, () -> new DiscoveryServer(
                        new DiscoveryConfiguration(LOOPBACK, occupied.getLocalPort(), running.tcp.port())));
            }
            tcp.connect("127.0.0.1", running.tcp.port());
            assertTrue(tcp.listFiles().files().isEmpty());
        }
    }

    @Test
    void closeCancelsPendingReceiveAndPreventsReuse() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (DatagramSocket peer = new DatagramSocket(0, LOOPBACK); DiscoveryClient discovery = new DiscoveryClient()) {
            peer.setSoTimeout(2000);
            CountDownLatch received = new CountDownLatch(1);
            Future<?> responder = executor.submit(() -> {
                try {
                    peer.receive(new DatagramPacket(new byte[129], 129));
                    received.countDown();
                } catch (IOException ex) {
                    throw new AssertionError(ex);
                }
            });
            Future<?> search = executor.submit(() -> {
                assertThrows(IOException.class, () -> discovery.discover(
                        List.of(DiscoveryTarget.unicast(LOOPBACK, peer.getLocalPort()))));
            });
            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> discovery.discover(
                    List.of(DiscoveryTarget.unicast(LOOPBACK, peer.getLocalPort()))));
            discovery.close();
            search.get(2, TimeUnit.SECONDS);
            responder.get(2, TimeUnit.SECONDS);
            assertThrows(IOException.class, () -> discovery.discover(
                    List.of(DiscoveryTarget.unicast(LOOPBACK, peer.getLocalPort()))));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void discoveryRunsAlongsideRealUploadAndDownload() throws Exception {
        try (Running running = new Running(); DiscoveryClient discovery = client(); TcpClient tcp = new TcpClient(5000)) {
            var selected = discovery.discover(List.of(running.target())).getFirst();
            tcp.connect(selected.host(), selected.tcpPort());
            byte[] bytes = new byte[1024 * 1024];
            new Random(77).nextBytes(bytes);
            bytes[0] = 0;
            Path source = Files.write(root.resolve("binary.bin"), bytes);
            var upload = new UploadService(tcp, 1L << 30, new UploadCheckpointStore(root.resolve("client")))
                    .uploadFile(source, progress -> {
                        if (progress.bytesTransferred() == 0) {
                            try {
                                assertEquals(List.of(selected), discovery.discover(List.of(running.target())));
                            } catch (IOException ex) {
                                throw new AssertionError(ex);
                            }
                        }
                    });
            assertEquals(1, tcp.listFiles().files().size());
            Path destination = root.resolve("received.bin");
            new DownloadService(tcp).downloadFile(upload.file().fileId(), destination, progress -> { });
            assertEquals(-1, Files.mismatch(source, destination));
            assertEquals(FileHashes.sha256(source), FileHashes.sha256(destination));
        }
    }

    @Test
    void broadcastTargetsAndConfigurationStayWithinV1Bounds() throws Exception {
        var targets = BroadcastTargets.forPort(49001);
        assertFalse(targets.isEmpty());
        assertEquals(targets.size(), new HashSet<>(targets).size());
        assertTrue(targets.stream().allMatch(target -> target.destination().getPort() == 49001));
        assertTrue(targets.stream().anyMatch(target -> target.destination().getAddress().getHostAddress()
                .equals("255.255.255.255")));
        assertEquals(new DiscoveryOptions(800, 3), DiscoveryOptions.defaults());
        assertThrows(IllegalArgumentException.class, () -> new DiscoveryOptions(801, 3));
        assertThrows(IllegalArgumentException.class, () -> new DiscoveryOptions(800, 4));
        assertThrows(IllegalArgumentException.class, () -> new DiscoveryOptions(0, 1));
        assertThrows(IllegalArgumentException.class, () -> BroadcastTargets.forPort(0));
        assertThrows(IllegalArgumentException.class, () -> DiscoveryTarget.unicast(LOOPBACK, 0));
        try (DiscoveryClient discovery = client()) {
            assertThrows(IllegalArgumentException.class, () -> discovery.discover(List.of()));
        }
    }
}
