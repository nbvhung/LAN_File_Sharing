package vn.edu.lanfileshare.client.ui;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.client.discovery.DiscoveredServer;
import vn.edu.lanfileshare.common.model.Messages.*;

class DiscoveryUiModelsTest {
    @Test
    void selectionIsExplicitAndManualTypingClearsItWithoutOverwritingInput() throws Exception {
        DiscoveredServer first = new DiscoveredServer(InetAddress.getLoopbackAddress(), 49010);
        DiscoveredServer second = new DiscoveredServer(InetAddress.getLoopbackAddress(), 49020);
        SwingUtilities.invokeAndWait(() -> {
            ConnectionPanel panel = new ConnectionPanel();
            AtomicInteger changes = new AtomicInteger();
            panel.onSelection(server -> {
                changes.incrementAndGet();
                panel.render(new UiSnapshot(UiState.DISCONNECTED, false, false, false, null, "",
                        List.of(), List.of(), List.of(first, second), server));
            });
            panel.host.setText("manual.local"); panel.port.setText("49100");
            panel.render(new UiSnapshot(UiState.DISCONNECTED, false, false, false, null, "",
                    List.of(), List.of(), List.of(first, second), null));
            assertEquals(0, changes.get()); assertNull(panel.servers.getSelectedItem());
            assertEquals("manual.local", panel.host.getText()); assertEquals("49100", panel.port.getText());
            assertTrue(panel.discover.isEnabled()); assertTrue(panel.connect.isEnabled());
            panel.servers.setSelectedIndex(2);
            assertEquals(1, changes.get()); assertEquals("49020", panel.port.getText());
            assertEquals(second.host(), panel.host.getText());
            panel.host.setText("another.local");
            assertNull(panel.servers.getSelectedItem()); assertEquals("another.local", panel.host.getText());
            panel.render(new UiSnapshot(UiState.DISCOVERING, false, true, false, null, "", List.of(), List.of()));
            assertFalse(panel.discover.isEnabled()); assertFalse(panel.connect.isEnabled());
            assertTrue(panel.cancelDiscovery.isEnabled()); assertFalse(panel.disconnect.isEnabled());
            panel.render(new UiSnapshot(UiState.CONNECTED, true, false, false, null, "", List.of(), List.of()));
            assertFalse(panel.discover.isEnabled()); assertFalse(panel.cancelDiscovery.isEnabled());
            assertTrue(panel.disconnect.isEnabled());
        });
    }

    @Test
    void safeDownloadNamesKeepExtensionButRejectServerPathsAndWindowsDevices() {
        UUID id = UUID.randomUUID();
        for (String name : List.of("report.pdf", "source.bin", "bai-tap.txt", "file name.zip")) {
            assertEquals(name, DownloadFileName.suggest(new FileInfo(id, name, 1, "a".repeat(64))));
        }
        for (String name : List.of("../secret", "x/y", "x\\y", "C:secret", "bad\nname", "CON", "con.txt",
                "NUL.bin", "LPT1.log", "name.", "name ", "bad?name", "bad\"name", "..")) {
            assertEquals("download-" + id + ".bin",
                    DownloadFileName.suggest(new FileInfo(id, name, 1, "a".repeat(64))), name);
        }
    }

    @Test
    void interruptedProgressUsesSavedOffsetAndResumeRequiresOriginalEndpoint() throws Exception {
        Endpoint original = new Endpoint("127.0.0.1", 49000);
        TransferItem before = new TransferItem(UUID.randomUUID(), null, "file", Direction.UPLOAD, original,
                Path.of("source"), null, 100, 1000, 10, 500, UiState.UPLOADING, "");
        TransferItem saved = before.identity(UUID.randomUUID(), Path.of("checkpoint"), 400,
                UiState.INTERRUPTED, "Saved");
        assertEquals(400, saved.bytes()); assertEquals(40, saved.percentage());
        assertEquals(0, saved.bytesPerSecond()); assertTrue(saved.resumable());
        SwingUtilities.invokeAndWait(() -> {
            TransferPanel panel = new TransferPanel();
            panel.render(new UiSnapshot(UiState.CONNECTED, true, false, false,
                    new Endpoint("another.local", 49000), "", List.of(), List.of(saved)));
            assertFalse(panel.resume.isEnabled());
            panel.render(new UiSnapshot(UiState.CONNECTED, true, false, false, original, "", List.of(), List.of(saved)));
            assertTrue(panel.resume.isEnabled());
        });
    }
}
