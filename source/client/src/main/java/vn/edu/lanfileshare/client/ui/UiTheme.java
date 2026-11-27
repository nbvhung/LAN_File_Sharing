package vn.edu.lanfileshare.client.ui;

import java.awt.*;
import java.util.Collections;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.plaf.FontUIResource;

/** Cài giao diện mặc định và định kiểu các thành phần Swing dùng chung. */
final class UiTheme {
    private static final Color BACKGROUND = new Color(241, 245, 249);
    static final Color INK = new Color(23, 39, 58);
    static final Color MUTED = new Color(90, 108, 126);
    static final Color ACCENT = new Color(0, 115, 110);
    private static final Color LINE = new Color(218, 227, 234);

    private UiTheme() {
    }

    static void install() throws Exception {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        Font font = new Font("Segoe UI", Font.PLAIN, 13);
        // Đổi font mặc định sau khi Look and Feel đã nạp các thiết lập của nó.
        for (Object key : Collections.list(UIManager.getDefaults().keys())) {
            if (UIManager.get(key) instanceof FontUIResource) {
                UIManager.put(key, new FontUIResource(font));
            }
        }
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("Label.foreground", INK);
        UIManager.put("Table.selectionBackground", new Color(219, 242, 239));
        UIManager.put("Table.selectionForeground", INK);
    }

    static void stylePanel(JPanel panel) {
        panel.setBackground(Color.WHITE);
        panel.setBorder(new CompoundBorder(new LineBorder(LINE), new EmptyBorder(14, 16, 14, 16)));
    }

    static JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 16));
        return label;
    }

    static void table(JTable table) {
        table.setRowHeight(32);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setFont(table.getFont().deriveFont(Font.BOLD));
        table.getTableHeader().setReorderingAllowed(false);
        table.setAutoCreateRowSorter(true);
    }

    static JButton button(String name) {
        JButton button = new JButton(name);
        button.setFocusPainted(false);
        button.setMargin(new Insets(7, 12, 7, 12));
        return button;
    }

    static JScrollPane scroll(Component component) {
        JScrollPane pane = new JScrollPane(component);
        pane.setBorder(new LineBorder(LINE));
        return pane;
    }
}
