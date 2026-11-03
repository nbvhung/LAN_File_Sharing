package vn.edu.lanfileshare.client.transfer;
import java.time.Duration;
import java.nio.file.Path;
import java.util.UUID;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;
/** A verified local file; COMPLETE has been sent to the server (v1 has no reply to COMPLETE). */
public record DownloadResult(UUID transferId, FileInfo file, Path destination, Duration elapsedTime) {}
