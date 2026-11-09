package vn.edu.lanfileshare.client.ui;

import java.awt.*;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import vn.edu.lanfileshare.client.discovery.DiscoveredServer;

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
    private Consumer<DiscoveredServer> selection = value -> { };
    private List<DiscoveredServer> displayed = List.of();
    private DiscoveredServer displayedSelection;
    private boolean updating;

    public ConnectionPanel() {
        super(new BorderLayout(12, 10));
        UiTheme.card(this, "Connection");
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
        servers.setPrototypeDisplayValue(null);
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
            if (!updating) {
                selection.accept((DiscoveredServer) servers.getSelectedItem());
            }
        });
        DocumentListener manualInput = new DocumentListener() {
            private void changed() {
                if (!updating && servers.getSelectedItem() != null) {
                    selection.accept(null);
                }
            }

            @Override
            public void insertUpdate(DocumentEvent event) {
                changed();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                changed();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                changed();
            }
        };
        host.getDocument().addDocumentListener(manualInput);
        port.getDocument().addDocumentListener(manualInput);
    }

    void onSelection(Consumer<DiscoveredServer> action) {
        selection = Objects.requireNonNull(action);
    }

    void render(UiSnapshot value) {
        ClientController.requireEdt();
        boolean editable = !value.connected() && !value.busy() && !value.closing();
        boolean searching = value.state() == UiState.DISCOVERING && value.busy() && !value.closing();
        host.setEnabled(editable);
        port.setEnabled(editable);
        udpPort.setEnabled(editable);
        connect.setEnabled(editable);
        discover.setEnabled(editable);
        cancelDiscovery.setEnabled(searching);
        servers.setEnabled(editable && !value.discoveredServers().isEmpty());
        disconnect.setEnabled(!value.closing() && value.state() != UiState.CONNECTING && !searching
                && (value.connected() || value.busy()));
        updating = true;
        try {
            if (!displayed.equals(value.discoveredServers())) {
                DefaultComboBoxModel<DiscoveredServer> choices = new DefaultComboBoxModel<>();
                choices.addElement(null);
                value.discoveredServers().forEach(choices::addElement);
                servers.setModel(choices);
                displayed = value.discoveredServers();
            }
            servers.setSelectedItem(value.selectedServer());
            if (value.selectedServer() != null && !Objects.equals(displayedSelection, value.selectedServer())) {
                host.setText(value.selectedServer().host());
                port.setText(Integer.toString(value.selectedServer().tcpPort()));
            }
            displayedSelection = value.selectedServer();
        } finally {
            updating = false;
        }
        status.setText(value.connected() ? "Connected" : value.state().toString());
        status.setToolTipText("Operation: " + value.state());
        status.setForeground(value.connected() || searching ? UiTheme.ACCENT
                : value.state() == UiState.FAILED ? new Color(170, 45, 45) : UiTheme.MUTED);
        searchStatus.setText(value.connected()
                ? "Connected. Disconnect before discovering or selecting another server."
                : searching ? "Searching ... You can cancel this search."
                : value.discoveredServers().isEmpty() ? "Discover nearby servers or enter an IP and TCP port manually."
                : value.discoveredServers().size() + " server(s) found. Choose one and click Connect, or enter an IP manually.");
    }
}
