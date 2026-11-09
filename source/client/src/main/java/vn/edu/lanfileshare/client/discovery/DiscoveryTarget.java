package vn.edu.lanfileshare.client.discovery;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;

/** A source interface and destination; null localAddress lets the OS select an IPv4 route. */
public record DiscoveryTarget(InetAddress localAddress, InetSocketAddress destination) {
    public DiscoveryTarget {
        Objects.requireNonNull(destination, "destination");
        if (destination.isUnresolved() || !(destination.getAddress() instanceof Inet4Address)
                || destination.getPort() < 1 || (localAddress != null && !(localAddress instanceof Inet4Address))) {
            throw new IllegalArgumentException("Discovery targets must be resolved IPv4 endpoints with a valid port");
        }
    }

    public static DiscoveryTarget unicast(InetAddress address, int udpPort) {
        return new DiscoveryTarget(null, new InetSocketAddress(address, udpPort));
    }
}
