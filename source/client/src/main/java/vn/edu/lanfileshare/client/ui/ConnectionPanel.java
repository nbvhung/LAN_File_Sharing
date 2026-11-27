package vn.edu.lanfileshare.client.ui;

import java.awt.*;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import vn.edu.lanfileshare.client.discovery.DiscoveredServer;

/** Hiển thị địa chỉ TCP nhập tay và kết quả tìm server qua UDP. */
public final class ConnectionPanel extends JPanel {
    final JTextField host = new JTextField("127.0.0.1", 17);
    final JTextField port = new JTextField("49000", 6);
    final JTextField udpPort = new JTextField("49001", 6);
    final JButton connect = UiTheme.button("Connect");
    final JButton disconnect = UiTheme.button("Disconnect");
    final JButton discover = UiTheme.button("Discover");
    final JButton cancelDiscovery = UiTheme.button("Cancel search");
    final JComboBox<DiscoveredServer> servers = new JComboBox<>();
    private final JLabel status = new JLabel("Disconnected");
    private final JLabel searchStatus = new JLabel("Manual IP entry is always available.");
    private Consumer<DiscoveredServer> serverSelectionListener = value -> { };
    private List<DiscoveredServer> displayedServers = List.of();
    private DiscoveredServer displayedSelection;
    private boolean updatingServerChoices;

    public ConnectionPanel() {
        super(new BorderLayout(12, 10));
        UiTheme.stylePanel(this);
        JPanel fields = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        fields.setOpaque(false);
        fields.add(new JLabel("Server IP"));
        fields.add(host);
        fields.add(new JLabel("TCP port  "));
        fields.add(port);
        fields.add(connect);
        fields.add(disconnect);
        JPanel connectionRow = new JPanel(new BorderLayout());
        connectionRow.setOpaque(false);
        connectionRow.add(fields, BorderLayout.CENTER);
        status.setFont(status.getFont().deriveFont(Font.BOLD));
        connectionRow.add(status, BorderLayout.EAST);
        add(connectionRow, BorderLayout.NORTH);

        JPanel discoveryRow = new JPanel(new GridBagLayout());
        discoveryRow.setOpaque(false);
        GridBagConstraints cell = new GridBagConstraints();
        cell.insets = new Insets(0, 5, 0, 5);
        cell.gridy = 0;
        discoveryRow.add(new JLabel("UDP port  "), cell);
        discoveryRow.add(udpPort, cell);
        discoveryRow.add(discover, cell);
        discoveryRow.add(cancelDiscovery, cell);
        cell.weightx = 1;
        cell.fill = GridBagConstraints.HORIZONTAL;
        servers.setPreferredSize(new Dimension(280, 30));
        servers.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected, boolean focused) {
                String text = value instanceof DiscoveredServer server
                        ? server.host() + " : " + server.tcpPort()
                        : "Select a discovered server ...";
                return super.getListCellRendererComponent(list, text, index, selected, focused);
            }
        });
        discoveryRow.add(servers, cell);
        add(discoveryRow, BorderLayout.CENTER);
        searchStatus.setForeground(UiTheme.MUTED);
        add(searchStatus, BorderLayout.SOUTH);

        host.setName("serverHost");
        port.setName("serverPort");
        udpPort.setName("udpPort");
        connect.setName("connect");
        disconnect.setName("disconnect");
        discover.setName("discover");
        cancelDiscovery.setName("cancelDiscovery");
        servers.setName("discoveredServers");
        host.setToolTipText("Type a server IP/hostname, or choose a discovery result");
        udpPort.setToolTipText("UDP discovery port; independent from the TCP file transfer port");
        servers.setToolTipText("Select an endpoint, then click Connect. Discovery never connects automatically.");
        servers.addActionListener(event -> {
            if (!updatingServerChoices) {
                serverSelectionListener.accept((DiscoveredServer) servers.getSelectedItem());
            }
        });
        DocumentListener manualInput = new DocumentListener() {
            private void clearSelectionForManualInput() {
                // Khi sửa host hoặc cổng bằng tay, bỏ lựa chọn cũ và giữ nội dung người dùng nhập.
                if (!updatingServerChoices && servers.getSelectedItem() != null) {
                    serverSelectionListener.accept(null);
                }
            }

            @Override
            public void insertUpdate(DocumentEvent event) {
                clearSelectionForManualInput();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                clearSelectionForManualInput();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                clearSelectionForManualInput();
            }
        };
        host.getDocument().addDocumentListener(manualInput);
        port.getDocument().addDocumentListener(manualInput);
    }

    void onSelection(Consumer<DiscoveredServer> action) {
        serverSelectionListener = Objects.requireNonNull(action);
    }

    void render(UiSnapshot snapshot) {
        ClientController.requireEdt();
        boolean canChooseServer = !snapshot.connected() && !snapshot.busy() && !snapshot.closing();
        // Lượt dò dùng UDP và chỉ bắt đầu khi chưa có kết nối TCP.
        boolean searching = snapshot.state() == UiState.DISCOVERING && snapshot.busy() && !snapshot.closing();
        host.setEnabled(canChooseServer);
        port.setEnabled(canChooseServer);
        udpPort.setEnabled(canChooseServer);
        connect.setEnabled(canChooseServer);
        discover.setEnabled(canChooseServer);
        cancelDiscovery.setEnabled(searching);
        servers.setEnabled(canChooseServer && !snapshot.discoveredServers().isEmpty());
        disconnect.setEnabled(!snapshot.closing() && snapshot.state() != UiState.CONNECTING && !searching
                && (snapshot.connected() || snapshot.busy()));
        renderServerChoices(snapshot);
        renderStatus(snapshot, searching);
    }

    private void renderServerChoices(UiSnapshot snapshot) {
        // Bỏ qua sự kiện phát sinh khi cập nhật danh sách hoặc tự điền host và cổng.
        updatingServerChoices = true;
        try {
            if (!displayedServers.equals(snapshot.discoveredServers())) {
                DefaultComboBoxModel<DiscoveredServer> choices = new DefaultComboBoxModel<>();
                choices.addElement(null);
                for (DiscoveredServer server : snapshot.discoveredServers()) {
                    choices.addElement(server);
                }
                servers.setModel(choices);
                displayedServers = snapshot.discoveredServers();
            }
            servers.setSelectedItem(snapshot.selectedServer());
            DiscoveredServer selectedServer = snapshot.selectedServer();
            if (selectedServer != null && !Objects.equals(displayedSelection, selectedServer)) {
                host.setText(selectedServer.host());
                port.setText(Integer.toString(selectedServer.tcpPort()));
            }
            displayedSelection = selectedServer;
        } finally {
            updatingServerChoices = false;
        }
    }

    private void renderStatus(UiSnapshot snapshot, boolean searching) {
        status.setText(snapshot.connected() ? "Connected" : snapshot.state().toString());
        status.setToolTipText("Operation: " + snapshot.state());
        if (snapshot.connected() || searching) {
            status.setForeground(UiTheme.ACCENT);
        } else if (snapshot.state() == UiState.FAILED) {
            status.setForeground(new Color(170, 45, 45));
        } else {
            status.setForeground(UiTheme.MUTED);
        }

        int serverCount = snapshot.discoveredServers().size();
        if (snapshot.connected()) {
            searchStatus.setText("Connected. Disconnect before discovering or selecting another server.");
        } else if (searching) {
            searchStatus.setText("Searching ... You can cancel this search.");
        } else if (serverCount == 0) {
            searchStatus.setText("Discover nearby servers or enter an IP and TCP port manually.");
        } else {
            searchStatus.setText(serverCount + " server(s) found. Choose one and click Connect, or enter an IP manually.");
        }
    }
}
