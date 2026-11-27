package vn.edu.lanfileshare.client.ui;

import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import javax.swing.*;
import javax.swing.text.BadLocationException;

public final class LogPanel extends JPanel {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_LINES = 1000;
    private static final int RETAINED_LINES = 800;

    private final JTextArea logText = new JTextArea(4, 60);

    public LogPanel() {
        super(new BorderLayout(0, 8));
        UiTheme.stylePanel(this);
        add(UiTheme.heading("Activity log"), BorderLayout.NORTH);
        logText.setEditable(false);
        logText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logText.setForeground(UiTheme.MUTED);
        logText.setLineWrap(true);
        logText.setWrapStyleWord(true);
        add(UiTheme.scroll(logText), BorderLayout.CENTER);
    }

    void append(String message) {
        ClientController.requireEdt();
        logText.append(LocalTime.now().format(TIME_FORMAT) + "  " + message + "\n");
        // Mỗi mục kết thúc bằng xuống dòng, nên JTextArea luôn đếm thêm một dòng trống.
        int lineCount = logText.getLineCount() - 1;
        if (lineCount > MAX_LINES) {
            try {
                // Giữ 800 dòng mới nhất để các lần ghi sau chưa phải cắt lại ngay.
                int linesToRemove = lineCount - RETAINED_LINES;
                int firstRetainedOffset = logText.getLineStartOffset(linesToRemove);
                logText.getDocument().remove(0, firstRetainedOffset);
            } catch (BadLocationException ex) {
                throw new IllegalStateException("Cannot trim activity log", ex);
            }
        }
        logText.setCaretPosition(logText.getDocument().getLength());
    }
}
