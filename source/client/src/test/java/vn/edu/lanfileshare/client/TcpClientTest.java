package vn.edu.lanfileshare.client;

import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.client.network.TcpClient;
import vn.edu.lanfileshare.server.network.*;

class TcpClientTest {
    @org.junit.jupiter.api.BeforeAll static void initializeEntropyBeforeSocketDeadlines() {
        java.util.UUID.randomUUID();
    }

    private TcpServer server() throws IOException {
        return new TcpServer(InetAddress.getLoopbackAddress(), 0, 8, 2000,
            new RequestDispatcher(List::of, (session, frame) -> fail("Unexpected transfer")));
    }
    @Test void localhostListReconnectAndShutdownReleaseSockets() throws Exception {
        try (TcpServer server = server(); TcpClient client = new TcpClient(2000)) {
            int port = server.port();
            server.start();
            client.connect("127.0.0.1", port);
            assertTrue(client.isConnected());
            assertTrue(client.listFiles().files().isEmpty());
            assertTrue(client.listFiles().files().isEmpty());
            client.disconnect();
            assertFalse(client.isConnected());
            // Abrupt reset while a partial frame is in progress.
            try (Socket abrupt = new Socket("127.0.0.1", port)) {
                abrupt.setSoLinger(true, 0);
                abrupt.getOutputStream().write(new byte[]{0, 0});
            }
            client.connect("127.0.0.1", port);
            assertTrue(client.listFiles().files().isEmpty());
            server.stop();
            assertThrows(IOException.class, client::listFiles);
            assertFalse(client.isConnected());
            try (ServerSocket replacement = new ServerSocket()) {
                replacement.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                assertEquals(port, replacement.getLocalPort());
            }
        }
    }
    @Test void refusedConnectionAndDisconnectedCall() throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        try (TcpClient client = new TcpClient(500)) {
            assertThrows(IOException.class, () -> client.connect("127.0.0.1", port));
            assertFalse(client.isConnected());
            assertThrows(IOException.class, client::listFiles);
        }
    }
    @Test void timeoutAndPeerEofCloseConnection() throws Exception {
        try (ServerSocket silent = new ServerSocket(0); TcpClient client = new TcpClient(200)) {
            client.connect("127.0.0.1", silent.getLocalPort());
            try (Socket peer = silent.accept()) {
                assertThrows(SocketTimeoutException.class, client::listFiles);
                assertFalse(client.isConnected());
            }
            client.connect("127.0.0.1", silent.getLocalPort());
            try (Socket peer = silent.accept()) { peer.shutdownOutput();
                assertThrows(EOFException.class, client::listFiles);
                assertFalse(client.isConnected());
            }
        }
    }
}
