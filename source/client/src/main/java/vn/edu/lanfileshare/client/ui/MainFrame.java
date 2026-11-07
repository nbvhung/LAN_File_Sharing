package vn.edu.lanfileshare.client.ui;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.nio.file.Path;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;

/** Composes panels and local file choosers; all business actions go to the controller. */
public final class MainFrame extends JFrame implements ClientView {
    private final ConnectionPanel connection = new ConnectionPanel();
    private final ServerFilePanel serverFiles = new ServerFilePanel();
    private final TransferPanel transfers = new TransferPanel();
    private final LogPanel logs = new LogPanel();
    private final JLabel footer = new JLabel("Ready");
    private final ClientController controller;

    public MainFrame(ClientBackend backend, String host, int port) {
        super("LAN File Sharing");
        ClientController.requireEdt();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(980, 740));
        setSize(1180, 900);
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(new EmptyBorder(20, 24, 14, 24));
        setContentPane(root);
        JPanel header = new JPanel(new BorderLayout(0, 14));
        JPanel branding = new JPanel(new BorderLayout());
        JLabel title = new JLabel("LAN File Sharing");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28));
        title.setForeground(UiTheme.INK);
        branding.add(title, BorderLayout.NORTH);
        JLabel subtitle = new JLabel("Your local network. Your files. Verified transfers.");
        subtitle.setForeground(UiTheme.MUTED);
        branding.add(subtitle, BorderLayout.SOUTH);
        JLabel badge = new JLabel("TCP v1  /  SHA-256  ");
        badge.setForeground(UiTheme.ACCENT);
        badge.setFont(badge.getFont().deriveFont(Font.BOLD));
        branding.add(badge, BorderLayout.EAST);
        header.add(branding, BorderLayout.NORTH);
        header.add(connection, BorderLayout.SOUTH);
        root.add(header, BorderLayout.NORTH);
        JSplitPane workspace = new JSplitPane(JSplitPane.VERTICAL_SPLIT, serverFiles, transfers);
        workspace.setResizeWeight(0.53);
        workspace.setDividerLocation(280);
        JSplitPane body = new JSplitPane(JSplitPane.VERTICAL_SPLIT, workspace, logs);
        body.setResizeWeight(0.8);
        body.setDividerLocation(530);
        workspace.setBorder(null);
        body.setBorder(null);
        serverFiles.setMinimumSize(new Dimension(200, 150));
        transfers.setMinimumSize(new Dimension(200, 180));
        logs.setMinimumSize(new Dimension(200, 110));
        body.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                body.setDividerLocation(Math.max(workspace.getMinimumSize().height, Math.min(body.getDividerLocation(),
                        body.getHeight() - logs.getMinimumSize().height - body.getDividerSize())));
                workspace.setDividerLocation(Math.min(workspace.getDividerLocation(),
                        workspace.getHeight() - transfers.getMinimumSize().height - workspace.getDividerSize()));
            }
        });
        root.add(body, BorderLayout.CENTER);
        footer.setForeground(UiTheme.MUTED);
        root.add(footer, BorderLayout.SOUTH);
        connection.host.setText(host);
        connection.port.setText(Integer.toString(port));
        controller = new ClientController(backend, this);
        connection.connect.addActionListener(event -> connect());
        connection.host.addActionListener(event -> connect());
        connection.port.addActionListener(event -> connect());
        connection.disconnect.addActionListener(event -> controller.disconnect());
        serverFiles.refresh.addActionListener(event -> controller.refresh());
        serverFiles.upload.addActionListener(event -> chooseUpload());
        serverFiles.download.addActionListener(event -> chooseDownload());
        transfers.resume.addActionListener(event -> {
            TransferItem item = transfers.selected();
            controller.resume(item == null ? null : item.rowId());
        });
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                UiSnapshot value = controller.snapshot();
                if (value.busy() && JOptionPane.showConfirmDialog(MainFrame.this,
                        "A task is active. Close the connection and keep any available checkpoint?",
                        "Close application", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)
                    return;
                controller.close();
            }
        });
        setLocationRelativeTo(null);
        logs.append("Ready. Connect using the server IP and TCP port. One transfer runs at a time.");
    }

    private void connect() {
        controller.connect(connection.host.getText(), connection.port.getText());
    }

    private void chooseUpload() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose a file to upload");
        chooser.setMultiSelectionEnabled(false);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            controller.upload(chooser.getSelectedFile().toPath());
    }

    private void chooseDownload() {
        FileInfo selected = serverFiles.selected();
        if (selected == null) {
            notice("Select a file", "Select a server file before clicking Download.", false);
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save downloaded file (existing files are not overwritten)");
        // Server metadata is display-only: do not turn an untrusted name into a path.
        chooser.setSelectedFile(new File("download-" + selected.fileId() + ".bin"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION)
            controller.download(selected, chooser.getSelectedFile().toPath());
    }

    public ClientController controller() {
        return controller;
    }

    @Override
    public void render(UiSnapshot value) {
        ClientController.requireEdt();
        connection.render(value);
        serverFiles.render(value);
        transfers.render(value);
        footer.setText(value.message());
        footer.setToolTipText(value.endpoint() == null ? null : value.endpoint().toString());
    }

    @Override
    public void log(String message) {
        logs.append(message);
    }

    @Override
    public void notice(String title, String message, boolean error) {
        ClientController.requireEdt();
        JOptionPane.showMessageDialog(this, message, title,
                error ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
    }

    @Override
    public void closed() {
        dispose();
    }
}
