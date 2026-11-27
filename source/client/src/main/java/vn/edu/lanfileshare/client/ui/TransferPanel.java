package vn.edu.lanfileshare.client.ui;

import java.awt.*;
import javax.swing.*;

/** Hiển thị lịch sử truyền file, tiến độ và nút Resume cho hàng được chọn. */
public final class TransferPanel extends JPanel {
    private static final int PROGRESS_STEPS_PER_PERCENT = 100;
    private static final int MAX_PROGRESS = 100 * PROGRESS_STEPS_PER_PERCENT;

    final TransferTableModel model = new TransferTableModel();
    final JTable table = new JTable(model);
    final JButton resume = UiTheme.button("Resume selected");
    private final JProgressBar progress = new JProgressBar(0, MAX_PROGRESS);
    private final JLabel summary = new JLabel("No transfers yet. Upload a file or select a file to download.");
    private final JTextField detail = new JTextField();
    private UiSnapshot latestSnapshot;

    public TransferPanel() {
        super(new BorderLayout(0, 10));
        UiTheme.stylePanel(this);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(UiTheme.heading("Transfers"), BorderLayout.WEST);
        top.add(resume, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);
        UiTheme.table(table);
        table.setName("transfers");
        table.getColumnModel().getColumn(0).setPreferredWidth(200);
        table.getColumnModel().getColumn(4).setPreferredWidth(270);
        add(UiTheme.scroll(table), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(0, 5));
        bottom.setOpaque(false);
        progress.setStringPainted(true);
        progress.setString("Ready");
        progress.setForeground(UiTheme.ACCENT);
        bottom.add(summary, BorderLayout.NORTH);
        bottom.add(progress, BorderLayout.CENTER);
        detail.setEditable(false);
        detail.setBorder(null);
        detail.setOpaque(false);
        detail.setForeground(UiTheme.MUTED);
        bottom.add(detail, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
        resume.setName("resume");
        table.getSelectionModel().addListSelectionListener(event -> updateDetail());
    }

    public TransferItem selectedTransfer() {
        int viewRow = table.getSelectedRow();
        return viewRow < 0 ? null : model.transferAt(table.convertRowIndexToModel(viewRow));
    }

    void render(UiSnapshot snapshot) {
        latestSnapshot = snapshot;
        int selectedViewRow = table.getSelectedRow();
        int previousRowCount = model.getRowCount();
        model.setRows(snapshot.transfers());
        int rowCount = model.getRowCount();
        if (rowCount > 0 && (selectedViewRow < 0 || rowCount > previousRowCount)) {
            int lastViewRow = table.convertRowIndexToView(rowCount - 1);
            table.setRowSelectionInterval(lastViewRow, lastViewRow);
            table.scrollRectToVisible(table.getCellRect(lastViewRow, 0, true));
        }
        updateDetail();
    }

    private void updateDetail() {
        TransferItem item = selectedTransfer();
        boolean connectedAndIdle = latestSnapshot != null && latestSnapshot.connected()
                && !latestSnapshot.busy() && !latestSnapshot.closing();
        // Checkpoint thuộc phiên truyền trên server cũ, nên phải kết nối lại đúng endpoint.
        boolean resumableSelection = item != null && item.canResume() && connectedAndIdle
                && item.endpoint().equals(latestSnapshot.endpoint());
        resume.setEnabled(resumableSelection);
        if (item == null) {
            return;
        }
        summary.setText(item.fileName() + "  |  " + UiFormat.bytes(item.transferredBytes()) + " / " + UiFormat.bytes(item.totalBytes())
                + "  |  " + UiFormat.bytes((long) item.bytesPerSecond()) + "/s  |  " + item.status());
        // Thanh tiến độ nhận số nguyên. Ví dụ 50% ứng với 5.000 trên thang 10.000.
        progress.setValue((int) (item.percentage() * PROGRESS_STEPS_PER_PERCENT));
        progress.setString(UiFormat.percentage(item.percentage()));
        detail.setText(item.detail());
        detail.setToolTipText(item.localPath() + (item.checkpoint() == null ? "" : " | " + item.checkpoint()));
    }
}
