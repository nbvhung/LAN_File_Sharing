package vn.edu.lanfileshare.client.transfer;

import java.time.Duration;

/** Common read-only progress API for a future UI; callbacks are synchronous. */
public interface TransferProgress {
    long bytesTransferred();

    long totalBytes();

    double percentage();

    Duration elapsedTime();

    double bytesPerSecond();

    TransferStatus status();
}
