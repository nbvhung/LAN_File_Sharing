package vn.edu.lanfileshare.client;

import vn.edu.lanfileshare.client.network.TcpClient;
import vn.edu.lanfileshare.common.protocol.DiscoveryProtocol;

/** Headless LIST_FILES demo; no Swing or file transfer. */
public final class ClientMain {
    private ClientMain() {}
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DiscoveryProtocol.DEFAULT_TCP_PORT;
        try (TcpClient client = new TcpClient()) {
            client.connect(host, port);
            var response = client.listFiles();
            System.out.println("FILE_LIST protocolVersion=" + response.protocolVersion()
                + " requestId=" + response.requestId() + " count=" + response.files().size());
            System.out.println("fileId | name | size (bytes) | sha256");
            for (var file : response.files()) {
                System.out.println(file.fileId() + " | " + file.name() + " | " + file.size() + " | " + file.sha256());
            }
        }
    }
}
