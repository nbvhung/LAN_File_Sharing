package vn.edu.lanfileshare.client.ui;

import java.awt.*;
import javax.swing.*;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;

public final class ServerFilePanel extends JPanel {
    final FileTableModel model = new FileTableModel();
    final JTable table = new JTable(model);
    final JButton refresh = UiTheme.button("Refresh"), upload = UiTheme.button("Upload file"),
            download = UiTheme.button("Download selected");
    private final JLabel count = new JLabel("Connect to view completed server files.");
    private final JTextField detail = new JTextField("Select a file to inspect its SHA-256.");
    private UiSnapshot latest;

    public ServerFilePanel() {
        super(new BorderLayout(0, 10));
        UiTheme.card(this, "Server files");
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(UiTheme.heading("Server files"), BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        actions.add(refresh);
        actions.add(upload);
        actions.add(download);
        top.add(actions, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);
        UiTheme.table(table);
        table.setName("serverFiles");
        table.getColumnModel().getColumn(0).setPreferredWidth(230);
        table.getColumnModel().getColumn(1).setPreferredWidth(110);
        table.getColumnModel().getColumn(2).setPreferredWidth(290);
        add(UiTheme.scroll(table), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(0, 4));
        bottom.setOpaque(false);
        count.setForeground(UiTheme.MUTED);
        bottom.add(count, BorderLayout.NORTH);
        detail.setEditable(false);
        detail.setBorder(null);
        detail.setOpaque(false);
        detail.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        bottom.add(detail, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        table.getSelectionModel().addListSelectionListener(event -> {
            FileInfo file = selected();
            detail.setText(file == null ? "Select a file to inspect its SHA-256." : "SHA-256  " + file.sha256());
            buttons();
        });
        refresh.setName("refresh");
        upload.setName("upload");
        download.setName("download");
    }

    public FileInfo selected() {
        int row = table.getSelectedRow();
        return row < 0 ? null : model.file(table.convertRowIndexToModel(row));
    }

    void render(UiSnapshot value) {
        latest = value;
        model.setRows(value.files());
        count.setText(!value.connected() ? "Connect to view completed server files."
                : value.files().isEmpty() ? "No files on this server."
                        : value.files().size() + " completed file(s) available");
        buttons();
    }

    private void buttons() {
        boolean ready = latest != null && latest.connected() && !latest.busy() && !latest.closing();
        refresh.setEnabled(ready);
        upload.setEnabled(ready);
        download.setEnabled(ready && selected() != null);
    }
}
