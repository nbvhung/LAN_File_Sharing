package vn.edu.lanfileshare.client.ui;

import java.util.List;
import javax.swing.table.AbstractTableModel;

public final class TransferTableModel extends AbstractTableModel {
    private static final String[] COLUMN_NAMES = { "File", "Direction", "Progress", "Status", "Transfer ID" };

    private List<TransferItem> rows = List.of();

    public void setRows(List<TransferItem> items) {
        ClientController.requireEdt();
        int previousRowCount = rows.size();
        rows = List.copyOf(items);
        if (previousRowCount != rows.size()) {
            fireTableDataChanged();
        } else if (!rows.isEmpty()) {
            // Cập nhật hàng hiện có để bảng giữ lựa chọn khi chỉ có tiến độ thay đổi.
            fireTableRowsUpdated(0, rows.size() - 1);
        }
    }

    public TransferItem transferAt(int modelRow) {
        return rows.get(modelRow);
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMN_NAMES.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMN_NAMES[column];
    }

    @Override
    public Object getValueAt(int row, int column) {
        TransferItem item = transferAt(row);
        return switch (column) {
        case 0 -> item.fileName();
        case 1 -> item.direction();
        case 2 -> UiFormat.percentage(item.percentage());
        case 3 -> item.status();
        default -> item.transferId() == null ? "Assigned after transfer starts" : item.transferId().toString();
        };
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return false;
    }
}
