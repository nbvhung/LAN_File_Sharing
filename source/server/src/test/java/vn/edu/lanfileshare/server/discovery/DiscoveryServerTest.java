package vn.edu.lanfileshare.server.discovery;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.common.protocol.DiscoveryProtocol;

class DiscoveryServerTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final String NONCE = "0123456789abcdef0123456789abcdef";

    @Test
    void localhostResponderEchoesNonceAndConfiguredTcpPort() throws Exception {
        try (DiscoveryServer server = new DiscoveryServer(new DiscoveryConfiguration(LOOPBACK, 0, 54321));
                DatagramSocket client = new DatagramSocket(0, LOOPBACK)) {
            server.start();
            client.setSoTimeout(2000);
            byte[] request = DiscoveryProtocol.request(NONCE);
            client.send(new DatagramPacket(request, request.length, LOOPBACK, server.port()));
            DatagramPacket response = new DatagramPacket(new byte[129], 129);
            client.receive(response);
            assertEquals(LOOPBACK, response.getAddress());
            assertEquals(54321, DiscoveryProtocol.parseResponse(response.getData(), response.getOffset(),
                    response.getLength(), NONCE).orElseThrow());
            assertTrue(server.isRunning());
            assertThrows(IllegalStateException.class, server::start);
        }
    }

    @Test
    void malformedAndOversizedPacketsProduceNoRepliesAndDoNotPoisonNextReceive() throws Exception {
        try (DiscoveryServer server = new DiscoveryServer(new DiscoveryConfiguration(LOOPBACK, 0, 49000));
                DatagramSocket client = new DatagramSocket(0, LOOPBACK)) {
            server.start();
            client.setSoTimeout(1000);
            for (byte[] invalid : new byte[][] { new byte[0], "x".getBytes(StandardCharsets.US_ASCII),
                    ("LANFS_DISCOVER/2 " + NONCE).getBytes(StandardCharsets.US_ASCII),
                    ("LANFS_DISCOVER/1 " + NONCE.toUpperCase()).getBytes(StandardCharsets.US_ASCII),
                    ("LANFS_DISCOVER/1 " + NONCE + "\n").getBytes(StandardCharsets.US_ASCII),
                    Arrays.copyOf(DiscoveryProtocol.request(NONCE), 129),
                    Arrays.copyOf(DiscoveryProtocol.request(NONCE), 4096), new byte[] { (byte) 0xff } }) {
                client.send(new DatagramPacket(invalid, invalid.length, LOOPBACK, server.port()));
            }
            String validNonce = "f".repeat(32);
            byte[] request = DiscoveryProtocol.request(validNonce);
            client.send(new DatagramPacket(request, request.length, LOOPBACK, server.port()));
            DatagramPacket response = new DatagramPacket(new byte[129], 129);
            client.receive(response);
            assertTrue(DiscoveryProtocol.parseResponse(response.getData(), response.getOffset(),
                    response.getLength(), validNonce).isPresent());
            client.setSoTimeout(100);
            assertThrows(SocketTimeoutException.class, () -> client.receive(response));
        }
    }

    @Test
    void shutdownReleasesPortAndJoinsWorker() throws Exception {
        DiscoveryServer server = new DiscoveryServer(new DiscoveryConfiguration(LOOPBACK, 0, 49000));
        int port = server.port();
        server.start();
        server.close();
        server.close();
        assertFalse(server.isRunning());
        assertThrows(IllegalStateException.class, server::start);
        assertFalse(Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && thread.getName().equals("lanfs-discovery-" + port)));
        try (DatagramSocket rebound = new DatagramSocket(port, LOOPBACK)) {
            assertEquals(port, rebound.getLocalPort());
        }
    }

    @Test
    void closeBeforeStartAndConflictingBindAreSafe() throws Exception {
        try (DatagramSocket occupied = new DatagramSocket(0, LOOPBACK)) {
            assertThrows(SocketException.class, () -> new DiscoveryServer(
                    new DiscoveryConfiguration(LOOPBACK, occupied.getLocalPort(), 49000)));
        }
        DiscoveryServer unused = new DiscoveryServer(new DiscoveryConfiguration(LOOPBACK, 0, 49000));
        int port = unused.port();
        unused.close();
        try (DatagramSocket rebound = new DatagramSocket(port, LOOPBACK)) {
            assertEquals(port, rebound.getLocalPort());
        }
        assertThrows(IllegalArgumentException.class, () -> new DiscoveryConfiguration(LOOPBACK, -1, 49000));
        assertThrows(IllegalArgumentException.class, () -> new DiscoveryConfiguration(LOOPBACK, 49001, 0));
    }
}
