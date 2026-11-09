package vn.edu.lanfileshare.server;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import vn.edu.lanfileshare.common.protocol.DiscoveryProtocol;
import vn.edu.lanfileshare.server.discovery.DiscoveryConfiguration;
import vn.edu.lanfileshare.server.discovery.DiscoveryServer;
import vn.edu.lanfileshare.server.network.*;
import vn.edu.lanfileshare.server.storage.*;

/** TCP bootstrap with persistent, verified file metadata and upload support. */
public final class ServerMain {
    public static void main(String[] args) throws Exception {
        args = vn.edu.lanfileshare.server.cli.CliArguments.decode(args);
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 49000;
        String bind = args.length > 1 ? args[1] : "0.0.0.0";
        Path storageDirectory = Path.of(args.length > 2 ? args[2] : "runtime/server");
        FileStorageService storage = new FileStorageService(new StorageConfiguration(storageDirectory));
        JsonFileCatalog catalog = new JsonFileCatalog(storage);
        long maxFileBytes = args.length > 3 ? Long.parseLong(args[3])
                : vn.edu.lanfileshare.server.transfer.UploadHandler.DEFAULT_MAX_FILE_BYTES;
        int maxConnections = args.length > 4 ? Integer.parseInt(args[4]) : 32;
        int timeoutMillis = args.length > 5 ? Integer.parseInt(args[5]) : 30_000;
        int udpPort = args.length > 6 ? Integer.parseInt(args[6]) : DiscoveryProtocol.DEFAULT_UDP_PORT;
        boolean discoveryEnabled = args.length <= 7 || Boolean.parseBoolean(args[7]);
        if (udpPort < 0 || udpPort > 65535) {
            throw new IllegalArgumentException("UDP port must be between 0 and 65535");
        }
        try (var transfers = new vn.edu.lanfileshare.server.transfer.TransferCoordinator(storage, catalog,
                maxFileBytes);
                TcpServer server = new TcpServer(InetAddress.getByName(bind), port, maxConnections, timeoutMillis,
                        new RequestDispatcher(catalog, transfers));
                DiscoveryServer discovery = startDiscovery(bind, udpPort, server.port(), discoveryEnabled)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    if (discovery != null) {
                        discovery.close();
                    }
                    server.close();
                    transfers.close();
                } catch (Exception ex) {
                    System.getLogger(ServerMain.class.getName()).log(System.Logger.Level.ERROR, "Shutdown failed", ex);
                }
            }));
            System.out.println("LAN File Sharing TCP listening on " + bind + ":" + server.port());
            System.out.println("Persistent catalog, TCP upload and download ready.");
            if (discovery != null) {
                System.out.println("UDP discovery listening on " + bind + ":" + discovery.port());
            }
            server.serve();
        }
    }

    private static DiscoveryServer startDiscovery(String bind, int udpPort, int tcpPort, boolean enabled) {
        if (!enabled) {
            return null;
        }
        try {
            DiscoveryServer discovery = new DiscoveryServer(
                    new DiscoveryConfiguration(InetAddress.getByName(bind), udpPort, tcpPort));
            discovery.start();
            return discovery;
        } catch (IOException ex) {
            System.getLogger(ServerMain.class.getName()).log(System.Logger.Level.WARNING,
                    "UDP discovery unavailable; direct TCP connections remain available", ex);
            return null;
        }
    }
}
