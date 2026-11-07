package vn.edu.lanfileshare.client.ui;

import java.nio.file.Path;
import java.util.UUID;
import vn.edu.lanfileshare.common.model.Messages.Direction;

/** Immutable EDT-owned presentation row. Local rowId is distinct from protocol transferId. */
public record TransferItem(UUID rowId, UUID transferId, String name, Direction direction, Endpoint endpoint,
        Path localPath, Path checkpoint, long bytes, long total, double percentage, double bytesPerSecond,
        UiState status, String detail) {
    public boolean resumable() {
        return status == UiState.INTERRUPTED && transferId != null && checkpoint != null;
    }

    public TransferItem progress(long value, long size, double percent, double speed, UiState state, String message) {
        return new TransferItem(rowId, transferId, name, direction, endpoint, localPath, checkpoint, value, size,
                percent, speed, state, message);
    }

    public TransferItem identity(UUID id, Path saved, long offset, UiState state, String message) {
        return new TransferItem(rowId, id, name, direction, endpoint, localPath, saved, offset, total,
                state == UiState.COMPLETED ? 100 : percentage, bytesPerSecond, state, message);
    }
}
