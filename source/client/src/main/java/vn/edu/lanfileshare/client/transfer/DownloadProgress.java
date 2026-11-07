package vn.edu.lanfileshare.client.transfer;

import java.time.Duration;

public record DownloadProgress(long bytesTransferred, long totalBytes, double percentage, Duration elapsedTime,
        double bytesPerSecond, TransferStatus status) implements TransferProgress {
}
