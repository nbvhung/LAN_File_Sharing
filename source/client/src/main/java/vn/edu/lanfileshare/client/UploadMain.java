package vn.edu.lanfileshare.client;

import java.nio.file.Path;
import vn.edu.lanfileshare.client.network.TcpClient;
import vn.edu.lanfileshare.client.transfer.*;

/** CLI: UploadMain <localFile> [host] [port] [maxFileBytes]. */
public final class UploadMain {
    private UploadMain() {}
    public static void main(String[] args) throws Exception {
        args = vn.edu.lanfileshare.common.cli.CliArguments.decode(args);
        if (args.length < 1 || args.length > 4) throw new IllegalArgumentException("Usage: UploadMain <localFile> [host] [port] [maxFileBytes]");
        Path file = Path.of(args[0]);
        String host = args.length > 1 ? args[1] : "127.0.0.1";
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 49000;
        long limit = args.length > 3 ? Long.parseLong(args[3]) : UploadService.DEFAULT_MAX_FILE_BYTES;
        try (TcpClient client = new TcpClient()) {
            client.connect(host, port);
            final int[] previousPercent = {-1};
            TransferResult result = new UploadService(client, limit).uploadFile(file, progress -> {
                int percent = (int)progress.percentage();
                if (percent != previousPercent[0]) {
                    System.out.printf("Upload %d%% (%d/%d bytes)%n", percent, progress.bytesTransferred(), progress.totalBytes());
                    previousPercent[0] = percent;
                }
            });
            System.out.println("UPLOAD_COMPLETE transferId=" + result.transferId() + " fileId=" + result.file().fileId()
                + " name=" + result.file().name() + " size=" + result.file().size() + " sha256=" + result.file().sha256());
        }
    }
}
