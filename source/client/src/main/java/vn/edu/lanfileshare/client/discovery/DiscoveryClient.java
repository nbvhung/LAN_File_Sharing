package vn.edu.lanfileshare.client.discovery;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import vn.edu.lanfileshare.common.protocol.DiscoveryProtocol;

/** Bounded synchronous discovery only. This class never opens a TCP connection. */
public final class DiscoveryClient implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(DiscoveryClient.class.getName());
    private final DiscoveryOptions options;
    private final SecureRandom random = new SecureRandom();
    private final AtomicBoolean searching = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<DatagramSocket> active = ConcurrentHashMap.newKeySet();

    public DiscoveryClient() {
        this(DiscoveryOptions.defaults());
    }

    public DiscoveryClient(DiscoveryOptions options) {
        this.options = Objects.requireNonNull(options);
    }

    public List<DiscoveredServer> discover() throws IOException {
        return discover(DiscoveryProtocol.DEFAULT_UDP_PORT);
    }

    public List<DiscoveredServer> discover(int udpPort) throws IOException {
        return discover(BroadcastTargets.forPort(udpPort));
    }

    /** Explicit targets support localhost diagnostics and independently configured discovery ports. */
    public List<DiscoveredServer> discover(List<DiscoveryTarget> destinations) throws IOException {
        List<DiscoveryTarget> targets = List.copyOf(new LinkedHashSet<>(destinations));
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("At least one discovery target is required");
        }
        if (!searching.compareAndSet(false, true)) {
            throw new IllegalStateException("Discovery is already running on this client");
        }
        try {
            checkOpen();
            Map<InetAddress, DatagramSocket> sockets = openSockets(targets);
            Set<DiscoveredServer> found = new LinkedHashSet<>();
            // One receive buffer per search; a sentinel byte catches oversized/truncated datagrams.
            byte[] buffer = new byte[DiscoveryProtocol.MAX_PACKET_BYTES + 1];
            for (int attempt = 0; attempt < options.attempts(); attempt++) {
                checkOpen();
                byte[] entropy = new byte[16];
                random.nextBytes(entropy);
                String nonce = HexFormat.of().formatHex(entropy);
                byte[] request = DiscoveryProtocol.request(nonce);
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.timeoutMillis());
                send(targets, sockets, request);
                List<DatagramSocket> receivers = new ArrayList<>(sockets.values());
                int index = 0;
                while (System.nanoTime() < deadline) {
                    checkOpen();
                    DatagramSocket socket = receivers.get(index++ % receivers.size());
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    // Round-robin polling shares ONE attempt deadline across every interface.
                    socket.setSoTimeout((int) Math.max(1, Math.min(20,
                            TimeUnit.NANOSECONDS.toMillis(remaining))));
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(packet);
                        var port = DiscoveryProtocol.parseResponse(packet.getData(), packet.getOffset(),
                                packet.getLength(), nonce);
                        if (port.isPresent() && !packet.getAddress().isAnyLocalAddress()
                                && !packet.getAddress().isMulticastAddress()) {
                            found.add(new DiscoveredServer(packet.getAddress(), port.getAsInt()));
                        }
                    } catch (SocketTimeoutException ex) {
                        // No reply in this short polling slice. The absolute attempt deadline is unchanged.
                    }
                }
            }
            return found.stream().sorted(Comparator.comparing(DiscoveredServer::host)
                    .thenComparingInt(DiscoveredServer::tcpPort)).toList();
        } finally {
            active.forEach(DatagramSocket::close);
            active.clear();
            searching.set(false);
        }
    }

    private Map<InetAddress, DatagramSocket> openSockets(List<DiscoveryTarget> targets) throws IOException {
        Map<InetAddress, DatagramSocket> sockets = new LinkedHashMap<>();
        IOException failures = new IOException("Cannot bind any discovery interface; direct TCP remains available");
        for (var target : targets) {
            InetAddress local = target.localAddress();
            if (sockets.containsKey(local)) {
                continue;
            }
            DatagramSocket socket = new DatagramSocket(null);
            try {
                active.add(socket);
                checkOpen();
                socket.setBroadcast(true);
                socket.bind(new InetSocketAddress(local == null
                        ? InetAddress.getByAddress(new byte[] { 0, 0, 0, 0 }) : local, 0));
                sockets.put(local, socket);
            } catch (IOException ex) {
                socket.close();
                active.remove(socket);
                failures.addSuppressed(ex);
                LOG.log(System.Logger.Level.WARNING, "Discovery interface binding failed", ex);
            }
        }
        checkOpen();
        if (sockets.isEmpty()) {
            throw failures;
        }
        return sockets;
    }

    private void send(List<DiscoveryTarget> targets, Map<InetAddress, DatagramSocket> sockets, byte[] request)
            throws IOException {
        int sent = 0;
        IOException failures = new IOException("No discovery datagram could be sent; direct TCP remains available");
        for (var target : targets) {
            checkOpen();
            DatagramSocket socket = sockets.get(target.localAddress());
            if (socket == null) {
                continue;
            }
            try {
                socket.send(new DatagramPacket(request, request.length, target.destination()));
                sent++;
            } catch (IOException ex) {
                failures.addSuppressed(ex);
                LOG.log(System.Logger.Level.WARNING, "Discovery send failed for " + target.destination(), ex);
            }
        }
        if (sent == 0) {
            throw failures;
        }
    }

    private void checkOpen() throws IOException {
        if (closed.get()) {
            throw new SocketException("Discovery client is closed");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Discovery interrupted");
        }
    }

    /** May be called by another thread to cancel a receive; the instance cannot be reused after close. */
    @Override
    public void close() {
        closed.set(true);
        active.forEach(DatagramSocket::close);
    }
}
