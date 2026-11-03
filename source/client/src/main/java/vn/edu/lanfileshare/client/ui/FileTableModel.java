package vn.edu.lanfileshare.client.ui;
import java.util.List;
import javax.swing.table.AbstractTableModel;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;
public final class FileTableModel extends AbstractTableModel {
    private List<FileInfo> rows = List.of();
    public void setRows(List<FileInfo> files) { ClientController.requireEdt(); if (!rows.equals(files)) { rows = List.copyOf(files); fireTableDataChanged(); } }
    public FileInfo file(int row) { return rows.get(row); }
    public int getRowCount() { return rows.size(); }
    public int getColumnCount() { return 3; }
    public String getColumnName(int column) { return new String[]{"File name", "Size (bytes)", "File ID"}[column]; }
    public Class<?> getColumnClass(int column) { return column == 1 ? Long.class : String.class; }
    public Object getValueAt(int row, int column) {
        FileInfo file = rows.get(row); return switch (column) { case 0 -> file.name(); case 1 -> file.size(); default -> file.fileId().toString(); };
    }
    public boolean isCellEditable(int row, int col) { return false; }
}
