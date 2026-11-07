package vn.edu.lanfileshare.client.ui;

import java.util.List;
import javax.swing.table.AbstractTableModel;

public final class TransferTableModel extends AbstractTableModel {
    private List<TransferItem> rows = List.of();

    public void setRows(List<TransferItem> items) {
        ClientController.requireEdt();
        int previous = rows.size();
        rows = List.copyOf(items);
        if (previous != rows.size())
            fireTableDataChanged();
        else if (!rows.isEmpty())
            fireTableRowsUpdated(0, rows.size() - 1);
    }

    public TransferItem item(int row) {
        return rows.get(row);
    }

    public int getRowCount() {
        return rows.size();
    }

    public int getColumnCount() {
        return 5;
    }

    public String getColumnName(int column) {
        return new String[] { "File", "Direction", "Progress", "Status", "Transfer ID" }[column];
    }

    public Object getValueAt(int row, int column) {
        TransferItem item = rows.get(row);
        return switch (column) {
        case 0 -> item.name();
        case 1 -> item.direction();
        case 2 -> UiFormat.percentage(item.percentage());
        case 3 -> item.status();
        default -> item.transferId() == null ? "Assigned after transfer starts" : item.transferId().toString();
        };
    }

    public boolean isCellEditable(int row, int column) {
        return false;
    }
}
