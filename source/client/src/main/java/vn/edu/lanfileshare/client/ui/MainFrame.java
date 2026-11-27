package vn.edu.lanfileshare.client.ui;

import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Path;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;
import vn.edu.lanfileshare.common.protocol.DiscoveryProtocol;

/** Dựng cửa sổ Swing và chuyển thao tác của người dùng cho controller. */
public final class MainFrame extends JFrame implements ClientView {
    private final ConnectionPanel connection = new ConnectionPanel();
    private final ServerFilePanel serverFiles = new ServerFilePanel();
    private final TransferPanel transfers = new TransferPanel();
    private final LogPanel logs = new LogPanel();
    private final JLabel footer = new JLabel("Ready");
    private final JTabbedPane workspace = new JTabbedPane();
    private boolean wasBusy;
    private boolean wasConnected;
    private int previousTransferCount;
    private final ClientController controller;

    public MainFrame(ClientBackend backend, String host, int port) {
        this(backend, new ServiceServerDiscovery(), host, port,
                DiscoveryProtocol.DEFAULT_UDP_PORT);
    }

    public MainFrame(ClientBackend backend, ServerDiscovery discovery, String host, int port, int udpPort) {
        super("LAN File Sharing");
        ClientController.requireEdt();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(980, 640));
        setSize(1180, 900);
        buildLayout();
        connection.host.setText(host);
        connection.port.setText(Integer.toString(port));
        connection.udpPort.setText(Integer.toString(udpPort));
        controller = new ClientController(backend, discovery, this);
        bindActions();
        setLocationRelativeTo(null);
        logs.append("Ready. Discover and choose a server, or enter an IP/port. One transfer runs at a time.");
    }

    private void buildLayout() {
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
        workspace.setName("workspaceTabs");
        workspace.addTab("Server files", serverFiles);
        workspace.addTab("Transfers", transfers);
        workspace.addTab("Activity log", logs);
        root.add(workspace, BorderLayout.CENTER);
        footer.setForeground(UiTheme.MUTED);
        root.add(footer, BorderLayout.SOUTH);
    }

    private void bindActions() {
        connection.onSelection(controller::selectServer);
        ActionListener discoverFromInput = event -> controller.discover(connection.udpPort.getText());
        connection.discover.addActionListener(discoverFromInput);
        connection.udpPort.addActionListener(discoverFromInput);
        connection.cancelDiscovery.addActionListener(event -> controller.cancelDiscovery());
        ActionListener connectFromInput = event -> controller.connect(connection.host.getText(),
                connection.port.getText());
        connection.connect.addActionListener(connectFromInput);
        connection.host.addActionListener(connectFromInput);
        connection.port.addActionListener(connectFromInput);
        connection.disconnect.addActionListener(event -> controller.disconnect());
        serverFiles.refresh.addActionListener(event -> controller.refresh());
        serverFiles.upload.addActionListener(event -> chooseUpload());
        serverFiles.download.addActionListener(event -> chooseDownload());
        transfers.resume.addActionListener(event -> {
            TransferItem item = transfers.selectedTransfer();
            controller.resume(item == null ? null : item.rowId());
        });
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                requestClose();
            }
        });
    }

    private void requestClose() {
        // Khi đang xử lý TCP, người dùng có thể giữ cửa sổ mở để tác vụ chạy tiếp.
        UiSnapshot snapshot = controller.snapshot();
        if (snapshot.busy() && snapshot.state() != UiState.DISCOVERING) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "A task is active. Close the connection and keep any available checkpoint?",
                    "Close application", JOptionPane.YES_NO_OPTION);
            if (answer != JOptionPane.YES_OPTION) {
                return;
            }
        }
        controller.close();
    }

    private void chooseUpload() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose a file to upload");
        chooser.setMultiSelectionEnabled(false);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            controller.upload(chooser.getSelectedFile().toPath());
        }
    }

    private void chooseDownload() {
        FileInfo selectedFile = serverFiles.selectedFile();
        if (selectedFile == null) {
            notice("Select a file", "Select a server file before clicking Download.", false);
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save downloaded file (existing files are not overwritten)");
        // Tên do server gửi chỉ là gợi ý. Đường dẫn đích do người dùng chọn.
        chooser.setSelectedFile(new File(DownloadFileName.suggest(selectedFile)));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            controller.download(selectedFile, chooser.getSelectedFile().toPath());
        }
    }

    public ClientController controller() {
        return controller;
    }

    @Override
    public void render(UiSnapshot snapshot) {
        ClientController.requireEdt();
        connection.render(snapshot);
        serverFiles.render(snapshot);
        transfers.render(snapshot);
        workspace.setTitleAt(0, "Server files (" + snapshot.files().size() + ")");
        workspace.setTitleAt(1, "Transfers (" + snapshot.transfers().size() + ")");
        boolean transferring = switch (snapshot.state()) {
            case UPLOADING, DOWNLOADING, VERIFYING, RESUMING -> true;
            default -> false;
        };
        // Chỉ chuyển tab lúc mới bắt đầu truyền hoặc kết nối để không giành lại tab đang xem.
        if (snapshot.busy() && transferring && (!wasBusy || snapshot.transfers().size() > previousTransferCount)) {
            workspace.setSelectedComponent(transfers);
        } else if (snapshot.connected() && !wasConnected) {
            workspace.setSelectedComponent(serverFiles);
        }
        wasBusy = snapshot.busy();
        wasConnected = snapshot.connected();
        previousTransferCount = snapshot.transfers().size();
        footer.setText(snapshot.message());
        footer.setToolTipText(snapshot.endpoint() == null ? null : snapshot.endpoint().toString());
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
