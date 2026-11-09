package vn.edu.lanfileshare.server.discovery;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import vn.edu.lanfileshare.common.protocol.DiscoveryProtocol;

/** Independent, single-use UDP responder. Malformed packets never produce a response. */
public final class DiscoveryServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(DiscoveryServer.class.getName());
    private final DatagramSocket socket;
    private final int tcpPort;
    private Thread worker;

    public DiscoveryServer(DiscoveryConfiguration configuration) throws SocketException {
        tcpPort = configuration.tcpPort();
        socket = new DatagramSocket(null);
        try {
            socket.bind(new InetSocketAddress(configuration.bindAddress(), configuration.udpPort()));
        } catch (SocketException | RuntimeException ex) {
            socket.close();
            throw ex;
        }
    }

    public int port() {
        return socket.getLocalPort();
    }

    public synchronized void start() {
        if (worker != null || socket.isClosed()) {
            throw new IllegalStateException("Discovery server already started or stopped");
        }
        worker = new Thread(this::receive, "lanfs-discovery-" + port());
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized boolean isRunning() {
        return worker != null && worker.isAlive() && !socket.isClosed();
    }

    private void receive() {
        LOG.log(System.Logger.Level.INFO, "action=discovery-start udpPort=" + port() + " tcpPort=" + tcpPort);
        // The extra byte detects a datagram exceeding the contract, even if the OS truncates it.
        byte[] buffer = new byte[DiscoveryProtocol.MAX_PACKET_BYTES + 1];
        try {
            while (!socket.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    var nonce = DiscoveryProtocol.parseRequest(packet.getData(), packet.getOffset(), packet.getLength());
                    if (nonce.isEmpty()) {
                        LOG.log(System.Logger.Level.DEBUG, "action=discovery-ignore reason=invalid-packet");
                        continue;
                    }
                    byte[] response = DiscoveryProtocol.response(nonce.get(), tcpPort);
                    socket.send(new DatagramPacket(response, response.length, packet.getSocketAddress()));
                } catch (IOException ex) {
                    if (socket.isClosed()) {
                        break;
                    }
                    LOG.log(System.Logger.Level.WARNING, "Discovery datagram I/O failed", ex);
                    if (ex instanceof SocketException) {
                        break;
                    }
                }
            }
        } finally {
            socket.close();
            LOG.log(System.Logger.Level.INFO, "action=discovery-stop");
        }
    }

    public void stop() {
        close();
    }

    @Override
    public void close() {
        Thread current;
        synchronized (this) {
            socket.close();
            current = worker;
        }
        if (current != null && current != Thread.currentThread()) {
            try {
                current.join(2000);
                if (current.isAlive()) {
                    LOG.log(System.Logger.Level.WARNING, "Discovery worker is still stopping");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
