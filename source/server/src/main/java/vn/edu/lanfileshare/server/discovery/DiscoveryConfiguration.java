package vn.edu.lanfileshare.server.discovery;

import java.net.InetAddress;
import java.util.Objects;
import vn.edu.lanfileshare.common.protocol.DiscoveryProtocol;

/** UDP port zero is available for tests; the advertised TCP port must already be bound. */
public record DiscoveryConfiguration(InetAddress bindAddress, int udpPort, int tcpPort) {
    public DiscoveryConfiguration {
        Objects.requireNonNull(bindAddress, "bindAddress");
        if (udpPort < 0 || udpPort > 65535 || tcpPort < 1 || tcpPort > 65535) {
            throw new IllegalArgumentException("Invalid discovery ports");
        }
    }

    public DiscoveryConfiguration(InetAddress bindAddress) {
        this(bindAddress, DiscoveryProtocol.DEFAULT_UDP_PORT, DiscoveryProtocol.DEFAULT_TCP_PORT);
    }
}
