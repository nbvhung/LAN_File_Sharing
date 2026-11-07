package vn.edu.lanfileshare.common.model;

import java.util.List;
import java.util.UUID;
import vn.edu.lanfileshare.common.error.ErrorCode;

/** Wire DTOs. Every control message carries version and requestId. */
public final class Messages {
    private Messages() {
    }

    public enum Direction {
        UPLOAD, DOWNLOAD
    }

    public record ListFiles(int protocolVersion, UUID requestId) {
    }

    public record FileInfo(UUID fileId, String name, long size, String sha256) {
    }

    public record FileList(int protocolVersion, UUID requestId, List<FileInfo> files) {
    }

    public record UploadInit(int protocolVersion, UUID requestId, String name, long size, String sha256) {
    }

    public record DownloadInit(int protocolVersion, UUID requestId, UUID fileId) {
    }

    public record TransferReady(int protocolVersion, UUID requestId, UUID transferId, Direction direction, UUID fileId,
            String name, long size, String sha256, long nextOffset) {
    }

    public record ProgressAck(int protocolVersion, UUID requestId, UUID transferId, long nextOffset) {
    }

    public record ResumeQuery(int protocolVersion, UUID requestId, UUID transferId, Direction direction, UUID fileId,
            long size, String sha256) {
    }

    public record TransferComplete(int protocolVersion, UUID requestId, UUID transferId, UUID fileId, long size,
            String sha256) {
    }

    public record ErrorMessage(int protocolVersion, UUID requestId, ErrorCode code, String message, UUID transferId) {
    }
}
