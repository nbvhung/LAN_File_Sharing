package vn.edu.lanfileshare.client;
import java.nio.file.Path;
import java.util.UUID;
import vn.edu.lanfileshare.client.network.TcpClient;
import vn.edu.lanfileshare.client.transfer.*;
/** DownloadMain <fileId> <destinationFile> [host] [port]. */
public final class DownloadMain {
    private DownloadMain() {}
    public static void main(String[] args) throws Exception {
        args = vn.edu.lanfileshare.common.cli.CliArguments.decode(args);
        if (args.length < 2 || args.length > 4) throw new IllegalArgumentException("Usage: DownloadMain <fileId> <destinationFile> [host] [port]");
        UUID fileId = UUID.fromString(args[0]); Path destination = Path.of(args[1]);
        try (TcpClient client = new TcpClient()) {
            client.connect(args.length > 2 ? args[2] : "127.0.0.1", args.length > 3 ? Integer.parseInt(args[3]) : 49000);
            final int[] previous = {-1};
            DownloadResult result = new DownloadService(client).downloadFile(fileId, destination, progress -> {
                int percent = (int)progress.percentage();
                if (percent != previous[0] || progress.status() == TransferStatus.VERIFYING) {
                    System.out.printf("Download %s %d%% (%d/%d bytes)%n", progress.status(), percent, progress.bytesTransferred(), progress.totalBytes());
                    previous[0] = percent;
                }
            });
            System.out.println("DOWNLOAD_COMPLETE fileId=" + result.file().fileId() + " size=" + result.file().size()
                + " sha256=" + result.file().sha256() + " destination=" + result.destination());
        }
    }
}
