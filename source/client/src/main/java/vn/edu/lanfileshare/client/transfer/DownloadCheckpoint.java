package vn.edu.lanfileshare.client.transfer;

import java.util.UUID;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;

public record DownloadCheckpoint(int schemaVersion, UUID requestId, UUID transferId, FileInfo file, String targetName,
        long nextOffset, TransferStatus status, long updatedAtEpochMillis, String prefixSha256) {
}
