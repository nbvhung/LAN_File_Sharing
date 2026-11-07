package vn.edu.lanfileshare.client.ui;

import java.awt.*;
import javax.swing.*;

public final class ConnectionPanel extends JPanel {
    final JTextField host = new JTextField("127.0.0.1", 17);
    final JTextField port = new JTextField("49000", 6);
    final JButton connect = UiTheme.button("Connect"), disconnect = UiTheme.button("Disconnect");
    private final JLabel status = new JLabel("Disconnected");

    public ConnectionPanel() {
        super(new BorderLayout(16, 8));
        UiTheme.card(this, "Connection");
        JPanel fields = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        fields.setOpaque(false);
        fields.add(new JLabel("Server IP"));
        fields.add(host);
        fields.add(new JLabel("TCP port  "));
        fields.add(port);
        fields.add(connect);
        fields.add(disconnect);
        host.setName("serverHost");
        port.setName("serverPort");
        connect.setName("connect");
        disconnect.setName("disconnect");
        host.setToolTipText("IP address or hostname of the TCP server");
        status.setFont(status.getFont().deriveFont(Font.BOLD));
        add(fields, BorderLayout.CENTER);
        add(status, BorderLayout.EAST);
    }

    void render(UiSnapshot value) {
        boolean editable = !value.connected() && !value.busy() && !value.closing();
        host.setEnabled(editable);
        port.setEnabled(editable);
        connect.setEnabled(editable);
        disconnect.setEnabled(
                !value.closing() && (value.connected() || value.busy()) && value.state() != UiState.CONNECTING);
        status.setText(value.state().toString());
        status.setForeground(value.connected() ? UiTheme.ACCENT
                : value.state() == UiState.FAILED ? new Color(170, 45, 45) : UiTheme.MUTED);
    }
}
