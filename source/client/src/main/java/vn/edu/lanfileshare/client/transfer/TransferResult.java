package vn.edu.lanfileshare.client.transfer;

import java.time.Duration;
import java.util.UUID;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;

/** Returned only after a validated TRANSFER_COMPLETE. */
public record TransferResult(UUID transferId, FileInfo file, Duration elapsedTime) {
}
