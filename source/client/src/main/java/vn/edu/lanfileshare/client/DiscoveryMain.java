package vn.edu.lanfileshare.client;

import java.net.InetAddress;
import java.util.List;
import vn.edu.lanfileshare.client.discovery.DiscoveryClient;
import vn.edu.lanfileshare.client.discovery.DiscoveryTarget;
import vn.edu.lanfileshare.client.network.TcpClient;
import vn.edu.lanfileshare.common.cli.CliArguments;
import vn.edu.lanfileshare.common.protocol.DiscoveryProtocol;

/** List discoveries; TCP LIST_FILES runs only if the caller explicitly supplies a 1-based server index. */
public final class DiscoveryMain {
    private DiscoveryMain() {
    }

    public static void main(String[] arguments) throws Exception {
        String[] args = CliArguments.decode(arguments);
        if (args.length > 3) {
            throw new IllegalArgumentException("Usage: DiscoveryMain [udpPort] [targetHost or -] [connectIndex or -1]");
        }
        int udpPort = args.length > 0 ? Integer.parseInt(args[0]) : DiscoveryProtocol.DEFAULT_UDP_PORT;
        String target = args.length > 1 ? args[1] : "-";
        int selection = args.length > 2 ? Integer.parseInt(args[2]) : -1;
        if (selection != -1 && selection < 1) {
            throw new IllegalArgumentException("Connect index must be 1-based, or -1 for discovery only");
        }
        try (DiscoveryClient discovery = new DiscoveryClient()) {
            var servers = target.equals("-") ? discovery.discover(udpPort)
                    : discovery.discover(List.of(DiscoveryTarget.unicast(InetAddress.getByName(target), udpPort)));
            System.out.println("DISCOVERED count=" + servers.size());
            for (int i = 0; i < servers.size(); i++) {
                var server = servers.get(i);
                System.out.println((i + 1) + " | " + server.host() + " | TCP " + server.tcpPort());
            }
            if (selection == -1) {
                System.out.println("Choose a server explicitly, or use run-client.ps1 / run-swing.ps1 with its IP and TCP port.");
                return;
            }
            if (selection > servers.size()) {
                throw new IllegalArgumentException("Selected server index is not present; direct TCP remains available");
            }
            var server = servers.get(selection - 1);
            try (TcpClient client = new TcpClient()) {
                client.connect(server.host(), server.tcpPort());
                var response = client.listFiles();
                System.out.println("FILE_LIST protocolVersion=" + response.protocolVersion()
                        + " requestId=" + response.requestId() + " count=" + response.files().size());
            }
        }
    }
}
