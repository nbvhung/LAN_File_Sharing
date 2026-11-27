package vn.edu.lanfileshare.client.ui;

import java.awt.*;
import javax.swing.*;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;

/** Hiển thị file trên server và các thao tác với hàng được chọn. */
public final class ServerFilePanel extends JPanel {
    final FileTableModel model = new FileTableModel();
    final JTable table = new JTable(model);
    final JButton refresh = UiTheme.button("Refresh");
    final JButton upload = UiTheme.button("Upload file");
    final JButton download = UiTheme.button("Download selected");
    private final JLabel count = new JLabel("Connect to view completed server files.");
    private final JTextField detail = new JTextField("Select a file to inspect its SHA-256.");
    private UiSnapshot latestSnapshot;

    public ServerFilePanel() {
        super(new BorderLayout(0, 10));
        UiTheme.stylePanel(this);
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
            FileInfo file = selectedFile();
            detail.setText(file == null ? "Select a file to inspect its SHA-256." : "SHA-256  " + file.sha256());
            updateButtons();
        });
        refresh.setName("refresh");
        upload.setName("upload");
        download.setName("download");
    }

    public FileInfo selectedFile() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            return null;
        }
        // Hàng trên màn hình đổi vị trí khi sort, còn model giữ thứ tự dữ liệu ban đầu.
        int modelRow = table.convertRowIndexToModel(viewRow);
        return model.fileAt(modelRow);
    }

    void render(UiSnapshot snapshot) {
        // Model có thể phát sự kiện chọn hàng ngay lúc đổi dữ liệu.
        latestSnapshot = snapshot;
        model.setRows(snapshot.files());
        if (!snapshot.connected()) {
            count.setText("Connect to view completed server files.");
        } else if (snapshot.files().isEmpty()) {
            count.setText("No files on this server.");
        } else {
            count.setText(snapshot.files().size() + " completed file(s) available");
        }
        updateButtons();
    }

    private void updateButtons() {
        boolean ready = latestSnapshot != null && latestSnapshot.connected() && !latestSnapshot.busy() && !latestSnapshot.closing();
        refresh.setEnabled(ready);
        upload.setEnabled(ready);
        download.setEnabled(ready && selectedFile() != null);
    }
}
