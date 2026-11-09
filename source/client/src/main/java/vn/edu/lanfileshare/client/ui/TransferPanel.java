package vn.edu.lanfileshare.client.ui;

import java.awt.*;
import javax.swing.*;

public final class TransferPanel extends JPanel {
    final TransferTableModel model = new TransferTableModel();
    final JTable table = new JTable(model);
    final JButton resume = UiTheme.button("Resume selected");
    private final JProgressBar progress = new JProgressBar(0, 10000);
    private final JLabel summary = new JLabel("No transfers yet. Upload a file or select a file to download.");
    private final JTextField detail = new JTextField();
    private UiSnapshot latest;

    public TransferPanel() {
        super(new BorderLayout(0, 10));
        UiTheme.card(this, "Transfers");
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

    public TransferItem selected() {
        int row = table.getSelectedRow();
        return row < 0 ? null : model.item(table.convertRowIndexToModel(row));
    }

    void render(UiSnapshot value) {
        latest = value;
        int selection = table.getSelectedRow();
        int previous = model.getRowCount();
        model.setRows(value.transfers());
        if ((selection < 0 || model.getRowCount() > previous) && model.getRowCount() > 0) {
            int last = table.convertRowIndexToView(model.getRowCount() - 1);
            table.setRowSelectionInterval(last, last);
            table.scrollRectToVisible(table.getCellRect(last, 0, true));
        }
        updateDetail();
    }

    private void updateDetail() {
        TransferItem item = selected();
        resume.setEnabled(latest != null && latest.connected() && !latest.busy() && !latest.closing() && item != null
                && item.resumable() && item.endpoint().equals(latest.endpoint()));
        if (item == null)
            return;
        summary.setText(item.name() + "  |  " + UiFormat.bytes(item.bytes()) + " / " + UiFormat.bytes(item.total())
                + "  |  " + UiFormat.bytes((long) item.bytesPerSecond()) + "/s  |  " + item.status());
        progress.setValue((int) (item.percentage() * 100));
        progress.setString(UiFormat.percentage(item.percentage()));
        detail.setText(item.detail());
        detail.setToolTipText(item.localPath() + (item.checkpoint() == null ? "" : " | " + item.checkpoint()));
    }
}
