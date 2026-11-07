package vn.edu.lanfileshare.client.transfer;

import java.util.UUID;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;

public record UploadCheckpoint(int schemaVersion, UUID transferId, FileInfo file, String sourcePath, long nextOffset,
        TransferStatus status, long lastActivityTime) {
}
