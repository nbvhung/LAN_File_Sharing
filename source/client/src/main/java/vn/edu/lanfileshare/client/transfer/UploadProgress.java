package vn.edu.lanfileshare.client.transfer;

import java.time.Duration;

/** Progress uses acknowledged bytes; 100 percent is emitted only after server completion. */
public record UploadProgress(long bytesTransferred, long totalBytes, double percentage, Duration elapsedTime,
        double bytesPerSecond) implements TransferProgress {
    @Override
    public TransferStatus status() {
        return percentage == 100 ? TransferStatus.COMPLETED
                : bytesTransferred == 0 ? TransferStatus.INITIALIZED : TransferStatus.TRANSFERRING;
    }
}
