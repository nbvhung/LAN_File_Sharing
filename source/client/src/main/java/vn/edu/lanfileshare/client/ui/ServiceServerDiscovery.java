package vn.edu.lanfileshare.client.ui;

import java.io.IOException;
import java.net.SocketException;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import vn.edu.lanfileshare.client.discovery.DiscoveredServer;
import vn.edu.lanfileshare.client.discovery.DiscoveryClient;
import vn.edu.lanfileshare.client.discovery.DiscoveryTarget;

/** Owns only the current search, so Cancel can close UDP without touching TCP. */
public final class ServiceServerDiscovery implements ServerDiscovery {
    @FunctionalInterface
    public interface Targets {
        List<DiscoveryTarget> forPort(int udpPort) throws IOException;
    }

    private final Supplier<DiscoveryClient> clients;
    private final Targets targets;
    private final Object lifecycle = new Object();
    private DiscoveryClient current;
    private boolean closed;

    public ServiceServerDiscovery() {
        this(DiscoveryClient::new, vn.edu.lanfileshare.client.discovery.BroadcastTargets::forPort);
    }

    /** Explicit targets allow real localhost tests without replacing the UDP protocol/service. */
    public ServiceServerDiscovery(Supplier<DiscoveryClient> clients, Targets targets) {
        this.clients = Objects.requireNonNull(clients);
        this.targets = Objects.requireNonNull(targets);
    }

    @Override
    public List<DiscoveredServer> discover(int udpPort) throws IOException {
        ClientController.requireWorker();
        DiscoveryClient search;
        synchronized (lifecycle) {
            if (closed) {
                throw new SocketException("Desktop discovery is closed");
            }
            if (current != null) {
                throw new IllegalStateException("Discovery already active");
            }
            search = clients.get();
            current = search;
        }
        try (search) {
            return search.discover(targets.forPort(udpPort));
        } finally {
            synchronized (lifecycle) {
                if (current == search) {
                    current = null;
                }
            }
        }
    }

    @Override
    public void cancel() {
        synchronized (lifecycle) {
            if (current != null) {
                current.close();
            }
        }
    }

    @Override
    public void close() {
        synchronized (lifecycle) {
            closed = true;
            cancel();
        }
    }
}
