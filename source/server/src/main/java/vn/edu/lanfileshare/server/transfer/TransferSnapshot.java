package vn.edu.lanfileshare.server.transfer;

import java.util.UUID;
import vn.edu.lanfileshare.common.model.Messages.Direction;
/** Local state schema, not a TCP DTO. No absolute paths are persisted. */
public record TransferSnapshot(int schemaVersion, UUID transferId, UUID fileId, UUID requestId,
    UUID sessionId, String name, long size, String sha256, Direction direction, long nextOffset,
    TransferState state, long updatedAtEpochMillis, String prefixSha256) {
    public TransferSnapshot(int schemaVersion, UUID transferId, UUID fileId, UUID requestId, UUID sessionId,
        String name, long size, String sha256, Direction direction, long nextOffset, TransferState state, long updatedAtEpochMillis) {
        this(schemaVersion, transferId, fileId, requestId, sessionId, name, size, sha256, direction, nextOffset, state, updatedAtEpochMillis, null);
    }
}
