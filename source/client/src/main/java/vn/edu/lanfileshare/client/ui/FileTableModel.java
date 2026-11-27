package vn.edu.lanfileshare.client.ui;

import java.util.List;
import javax.swing.table.AbstractTableModel;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;

public final class FileTableModel extends AbstractTableModel {
    private static final String[] COLUMN_NAMES = { "File name", "Size (bytes)", "File ID" };
    private static final int SIZE_COLUMN = 1;

    private List<FileInfo> rows = List.of();

    public void setRows(List<FileInfo> files) {
        ClientController.requireEdt();
        if (!rows.equals(files)) {
            rows = List.copyOf(files);
            fireTableDataChanged();
        }
    }

    public FileInfo fileAt(int modelRow) {
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
    public Class<?> getColumnClass(int column) {
        // JTable dùng kiểu Long để sắp xếp theo kích thước thực.
        return column == SIZE_COLUMN ? Long.class : String.class;
    }

    @Override
    public Object getValueAt(int row, int column) {
        FileInfo file = fileAt(row);
        return switch (column) {
        case 0 -> file.name();
        case SIZE_COLUMN -> file.size();
        default -> file.fileId().toString();
        };
    }

    @Override
    public boolean isCellEditable(int row, int col) {
        return false;
    }
}
