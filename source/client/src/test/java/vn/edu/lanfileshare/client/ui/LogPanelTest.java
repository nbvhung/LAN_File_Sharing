package vn.edu.lanfileshare.client.ui;

import java.awt.BorderLayout;
import java.util.List;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogPanelTest {
    @Test
    void trimsOnlyAboveLimitAndKeepsExactlyTheNewestEightHundredLines() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LogPanel panel = new LogPanel();
            BorderLayout layout = (BorderLayout) panel.getLayout();
            JScrollPane scroll = (JScrollPane) layout.getLayoutComponent(BorderLayout.CENTER);
            JTextArea log = (JTextArea) scroll.getViewport().getView();
            for (int line = 1; line <= 1000; line++) {
                panel.append("entry " + line);
            }
            assertEquals(1000, log.getText().lines().count());

            panel.append("entry 1001");
            List<String> retained = log.getText().lines().toList();
            assertEquals(800, retained.size());
            assertTrue(retained.getFirst().endsWith("  entry 202"));
            assertTrue(retained.getLast().endsWith("  entry 1001"));
            assertEquals(log.getDocument().getLength(), log.getCaretPosition());
        });
    }
}
