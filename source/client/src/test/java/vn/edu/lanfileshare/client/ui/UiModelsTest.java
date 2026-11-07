package vn.edu.lanfileshare.client.ui;

import java.net.*;
import java.nio.file.*;
import java.util.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.protocol.ProtocolException;

class UiModelsTest {
    @Test
    void panelButtonsRespectSelectionBusyConnectionAndResumeState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ServerFilePanel files = new ServerFilePanel();
            TransferPanel transfers = new TransferPanel();
            ConnectionPanel connection = new ConnectionPanel();
            Endpoint endpoint = new Endpoint("127.0.0.1", 49000);
            FileInfo file = new FileInfo(UUID.randomUUID(), "report.txt", 1024, "a".repeat(64));
            UiSnapshot disconnected = new UiSnapshot(UiState.DISCONNECTED, false, false, false, endpoint, "", List.of(),
                    List.of());
            files.render(disconnected);
            connection.render(disconnected);
            assertFalse(files.refresh.isEnabled());
            assertTrue(connection.connect.isEnabled());
            UiSnapshot ready = new UiSnapshot(UiState.CONNECTED, true, false, false, endpoint, "", List.of(file),
                    List.of());
            files.render(ready);
            connection.render(ready);
            assertTrue(files.upload.isEnabled());
            assertFalse(files.download.isEnabled());
            files.table.setRowSelectionInterval(0, 0);
            assertEquals(file, files.selected());
            assertTrue(files.download.isEnabled());
            TransferItem interrupted = new TransferItem(UUID.randomUUID(), UUID.randomUUID(), "report.txt",
                    Direction.UPLOAD, endpoint, Path.of("report.txt"), Path.of("checkpoint.json"), 512, 1024, 50, 100,
                    UiState.INTERRUPTED, "Saved");
            UiSnapshot resumable = new UiSnapshot(UiState.CONNECTED, true, false, false, endpoint, "", List.of(file),
                    List.of(interrupted));
            transfers.render(resumable);
            assertTrue(transfers.resume.isEnabled());
            UiSnapshot busy = new UiSnapshot(UiState.RESUMING, true, true, false, endpoint, "", List.of(file),
                    List.of(interrupted));
            files.render(busy);
            transfers.render(busy);
            connection.render(busy);
            assertFalse(files.refresh.isEnabled());
            assertFalse(files.upload.isEnabled());
            assertFalse(files.download.isEnabled());
            assertFalse(transfers.resume.isEnabled());
            assertTrue(connection.disconnect.isEnabled());
            TransferItem completed = interrupted.identity(interrupted.transferId(), interrupted.checkpoint(), 1024,
                    UiState.COMPLETED, "Done");
            transfers.render(new UiSnapshot(UiState.COMPLETED, true, false, false, endpoint, "", List.of(file),
                    List.of(completed)));
            assertFalse(transfers.resume.isEnabled());
        });
    }

    @Test
    void fileTableSortsNumericSizesAndKeepsIdsDistinct() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FileTableModel model = new FileTableModel();
            var a = new FileInfo(UUID.randomUUID(), "same.txt", 20, "a".repeat(64));
            var b = new FileInfo(UUID.randomUUID(), "same.txt", 100, "b".repeat(64));
            model.setRows(List.of(a, b));
            assertEquals(Long.class, model.getColumnClass(1));
            assertNotEquals(model.getValueAt(0, 2), model.getValueAt(1, 2));
            assertFalse(model.isCellEditable(0, 0));
        });
    }

    @Test
    void errorMessagesAreActionableWithoutStackTraces() {
        assertTrue(ErrorMessages.describe(new ConnectException()).contains("Cannot connect"));
        assertTrue(ErrorMessages.describe(new SocketTimeoutException()).contains("timeout"));
        assertTrue(ErrorMessages.describe(new AccessDeniedException("local")).contains("permission"));
        assertTrue(ErrorMessages.describe(new ProtocolException(ErrorCode.HASH_MISMATCH, "internal secret", false))
                .contains("Hash mismatch"));
        assertFalse(ErrorMessages.describe(new ProtocolException(ErrorCode.HASH_MISMATCH, "internal secret", false))
                .contains("internal secret"));
        assertThrows(IllegalArgumentException.class, () -> Endpoint.parse("127.0.0.1", "70000"));
        assertThrows(IllegalArgumentException.class, () -> Endpoint.parse("bad host", "49000"));
    }
}
