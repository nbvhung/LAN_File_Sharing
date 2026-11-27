package vn.edu.lanfileshare.client.ui;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.UUID;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import vn.edu.lanfileshare.common.cli.CliArguments;
import vn.edu.lanfileshare.common.protocol.DiscoveryProtocol;

/** Đọc tham số khởi động, chuẩn bị backend rồi mở cửa sổ trên Swing EDT. */
public final class ClientSwingMain {
    private ClientSwingMain() {
    }

    public static void main(String[] arguments) throws Exception {
        String[] args = CliArguments.decode(arguments);
        if (args.length > 4) {
            throw new IllegalArgumentException("Usage: ClientSwingMain [host] [port] [checkpointDirectory] [udpPort]");
        }
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int tcpPort = args.length > 1 ? Integer.parseInt(args[1]) : 49000;
        Endpoint endpoint = new Endpoint(host, tcpPort);
        int udpPort = args.length > 3 ? Integer.parseInt(args[3])
                : DiscoveryProtocol.DEFAULT_UDP_PORT;
        Endpoint.requireValidPort(udpPort, "UDP");
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Swing requires a desktop display. Use run-client.ps1 for the CLI.");
            return;
        }
        // Khởi tạo bộ sinh UUID trên luồng main để lần tạo hàng truyền đầu tiên không phải chờ trên EDT.
        UUID.randomUUID();
        Path checkpointDirectory = args.length > 2 ? Path.of(args[2])
                : Path.of(System.getProperty("user.home"), ".lan-file-sharing", "client");
        ClientBackend backend = new ServiceClientBackend(checkpointDirectory);
        SwingUtilities.invokeLater(() -> openWindow(backend, endpoint, udpPort));
    }

    private static void openWindow(ClientBackend backend, Endpoint endpoint, int udpPort) {
        try {
            UiTheme.install();
            MainFrame window = new MainFrame(backend, new ServiceServerDiscovery(), endpoint.host(), endpoint.port(),
                    udpPort);
            window.setVisible(true);
        } catch (Exception ex) {
            System.getLogger(ClientSwingMain.class.getName()).log(System.Logger.Level.ERROR, "Cannot start desktop",
                    ex);
            JOptionPane.showMessageDialog(null, "Cannot start desktop: " + ErrorMessages.describe(ex),
                    "Startup failed", JOptionPane.ERROR_MESSAGE);
        }
    }
}
