package vn.edu.lanfileshare.client.ui;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import javax.swing.*;
import javax.swing.text.BadLocationException;
public final class LogPanel extends JPanel {
    private final JTextArea text = new JTextArea(4, 60);
    public LogPanel() {
        super(new BorderLayout(0, 8)); UiTheme.card(this, "Activity"); add(UiTheme.heading("Activity log"), BorderLayout.NORTH);
        text.setEditable(false); text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        text.setForeground(UiTheme.MUTED); text.setLineWrap(true); text.setWrapStyleWord(true); add(UiTheme.scroll(text), BorderLayout.CENTER);
    }
    void append(String message) {
        ClientController.requireEdt(); text.append(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + message + "\n");
        if (text.getLineCount() > 1000) {
            try { text.getDocument().remove(0, text.getLineEndOffset(text.getLineCount() - 800)); }
            catch (BadLocationException ex) { throw new IllegalStateException("Cannot trim activity log", ex); }
        }
        text.setCaretPosition(text.getDocument().getLength());
    }
}
