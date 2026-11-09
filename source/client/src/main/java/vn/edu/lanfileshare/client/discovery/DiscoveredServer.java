package vn.edu.lanfileshare.client.discovery;

import java.net.InetAddress;
import java.util.Objects;

/** Endpoint identity comes from the UDP packet source and the validated service port. */
public record DiscoveredServer(InetAddress address, int tcpPort) {
    public DiscoveredServer {
        Objects.requireNonNull(address, "address");
        if (address.isAnyLocalAddress() || address.isMulticastAddress() || tcpPort < 1 || tcpPort > 65535) {
            throw new IllegalArgumentException("Invalid discovered endpoint");
        }
    }

    public String host() {
        return address.getHostAddress();
    }
}
