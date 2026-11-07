package vn.edu.lanfileshare.client;

import java.nio.file.Path;
import java.util.UUID;
import vn.edu.lanfileshare.client.network.TcpClient;
import vn.edu.lanfileshare.client.transfer.*;

/** ResumeUploadMain <transferId> <host> <port> <checkpointDirectory> <maxFileBytes> [sourceFile]. */
public final class ResumeUploadMain {
    private ResumeUploadMain() {
    }

    public static void main(String[] args) throws Exception {
        args = vn.edu.lanfileshare.common.cli.CliArguments.decode(args);
        if (args.length < 5 || args.length > 6)
            throw new IllegalArgumentException(
                    "Usage: ResumeUploadMain <transferId> <host> <port> <checkpointDirectory> <maxFileBytes> [sourceFile]");
        UUID id = UUID.fromString(args[0]);
        UploadCheckpointStore store = new UploadCheckpointStore(Path.of(args[3]));
        Path source = args.length == 6 ? Path.of(args[5]) : Path.of(store.load(id).sourcePath());
        try (TcpClient client = new TcpClient()) {
            client.connect(args[1], Integer.parseInt(args[2]));
            ResumeResult<TransferResult> resumed = new UploadService(client, Long.parseLong(args[4]), store)
                    .resumeUpload(id, source, p -> {
                    });
            System.out.println("RESUME_UPLOAD_COMPLETE transferId=" + id + " fileId=" + resumed.result().file().fileId()
                    + " resumedFrom=" + resumed.resumedFromOffset() + " payloadBytes="
                    + resumed.payloadBytesThisAttempt() + " sha256=" + resumed.result().file().sha256());
        }
    }
}
