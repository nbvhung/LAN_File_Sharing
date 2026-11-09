package vn.edu.lanfileshare.client.discovery;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Directed broadcasts are sent from their own interface, including on multihomed hosts. */
public final class BroadcastTargets {
    private static final System.Logger LOG = System.getLogger(BroadcastTargets.class.getName());

    private BroadcastTargets() {
    }

    public static List<DiscoveryTarget> forPort(int udpPort) throws IOException {
        if (udpPort < 1 || udpPort > 65535) {
            throw new IllegalArgumentException("UDP port must be between 1 and 65535");
        }
        Set<DiscoveryTarget> targets = new LinkedHashSet<>();
        var interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            try {
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (var address : network.getInterfaceAddresses()) {
                    if (address.getAddress() instanceof Inet4Address && address.getBroadcast() != null) {
                        targets.add(new DiscoveryTarget(address.getAddress(),
                                new InetSocketAddress(address.getBroadcast(), udpPort)));
                    }
                }
            } catch (SocketException ex) {
                LOG.log(System.Logger.Level.WARNING, "Cannot inspect network interface " + network.getName(), ex);
            }
        }
        // Additional fallback, never a substitute for the interface-specific directed broadcasts.
        targets.add(DiscoveryTarget.unicast(InetAddress.getByAddress(new byte[] { -1, -1, -1, -1 }), udpPort));
        return List.copyOf(targets);
    }
}
