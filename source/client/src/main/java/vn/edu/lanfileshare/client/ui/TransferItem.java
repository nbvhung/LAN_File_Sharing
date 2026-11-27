package vn.edu.lanfileshare.client.ui;

import java.nio.file.Path;
import java.util.UUID;
import vn.edu.lanfileshare.common.model.Messages.Direction;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;

/**
 * Lưu thông tin của một lượt upload hoặc download cho bảng Transfers.
 * rowId giúp chọn và cập nhật đúng hàng ngay cả khi server chưa cấp transferId.
 * transferId được dùng để tìm lại phiên truyền khi Resume.
 */
public record TransferItem(UUID rowId, UUID transferId, String fileName, Direction direction, Endpoint endpoint,
        Path localPath, Path checkpoint, long transferredBytes, long totalBytes, double percentage, double bytesPerSecond,
        UiState status, String detail) {
    static final double MAX_INCOMPLETE_PERCENTAGE = 99.9;

    static TransferItem forUpload(Endpoint endpoint, Path source) {
        return new TransferItem(UUID.randomUUID(), null, source.getFileName().toString(), Direction.UPLOAD,
                endpoint, source.toAbsolutePath().normalize(), null, 0, 0, 0, 0, UiState.VERIFYING,
                "Checking source and calculating SHA-256 ...");
    }

    static TransferItem forDownload(Endpoint endpoint, FileInfo file, Path destination) {
        return new TransferItem(UUID.randomUUID(), null, file.name(), Direction.DOWNLOAD, endpoint,
                destination.toAbsolutePath().normalize(), null, 0, file.size(), 0, 0, UiState.DOWNLOADING,
                "Requesting download ...");
    }

    /** Cần transfer ID để hỏi server và checkpoint để đọc thông tin của lần truyền bị ngắt. */
    public boolean canResume() {
        return status == UiState.INTERRUPTED && transferId != null && checkpoint != null;
    }

    /** Tạo bản mới của cùng hàng, với dữ liệu tiến độ và trạng thái được cập nhật. */
    public TransferItem withProgress(long transferredBytes, long totalBytes, double percentage, double bytesPerSecond,
            UiState newStatus, String newDetail) {
        return new TransferItem(rowId, transferId, fileName, direction, endpoint, localPath, checkpoint,
                transferredBytes, totalBytes, percentage, bytesPerSecond, newStatus, newDetail);
    }

    public TransferItem completed(UUID confirmedTransferId, long fileSize, String newDetail) {
        return new TransferItem(rowId, confirmedTransferId, fileName, direction, endpoint, localPath, checkpoint,
                fileSize, fileSize, 100, bytesPerSecond, UiState.COMPLETED, newDetail);
    }

    public TransferItem interruptedAt(UUID interruptedTransferId, Path savedCheckpoint, long savedOffset,
            String newDetail) {
        // Tính phần trăm từ offset đã lưu vì Resume tiếp tục ở đúng byte này.
        double savedPercentage = totalBytes == 0 ? 0
                : Math.min(MAX_INCOMPLETE_PERCENTAGE, 100.0 * savedOffset / totalBytes);
        return new TransferItem(rowId, interruptedTransferId, fileName, direction, endpoint, localPath, savedCheckpoint,
                savedOffset, totalBytes, savedPercentage, 0, UiState.INTERRUPTED, newDetail);
    }
}
