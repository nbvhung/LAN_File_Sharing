package vn.edu.lanfileshare.client.ui;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import javax.swing.*;
import vn.edu.lanfileshare.common.cli.CliArguments;

/** Desktop entry point; service construction and startup configuration happen off the EDT. */
public final class ClientSwingMain {
    private ClientSwingMain() {
    }

    public static void main(String[] arguments) throws Exception {
        String[] args = CliArguments.decode(arguments);
        if (args.length > 3)
            throw new IllegalArgumentException("Usage: ClientSwingMain [host] [port] [checkpointDirectory]");
        Endpoint endpoint = new Endpoint(args.length > 0 ? args[0] : "127.0.0.1",
                args.length > 1 ? Integer.parseInt(args[1]) : 49000);
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Swing requires a desktop display. Use run-client.ps1 for the CLI.");
            return;
        }
        java.util.UUID.randomUUID(); // Warm entropy before any Swing action.
        ClientBackend backend = new ServiceClientBackend(args.length > 2 ? Path.of(args[2])
                : Path.of(System.getProperty("user.home"), ".lan-file-sharing", "client"));
        SwingUtilities.invokeLater(() -> {
            try {
                UiTheme.install();
                new MainFrame(backend, endpoint.host(), endpoint.port()).setVisible(true);
            } catch (Exception ex) {
                System.getLogger(ClientSwingMain.class.getName()).log(System.Logger.Level.ERROR, "Cannot start desktop",
                        ex);
                JOptionPane.showMessageDialog(null, "Cannot start desktop: " + ErrorMessages.describe(ex),
                        "Startup failed", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}
