package vn.edu.lanfileshare.client;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import vn.edu.lanfileshare.client.network.TcpClient;
import vn.edu.lanfileshare.client.transfer.*;
import vn.edu.lanfileshare.common.io.FileHashes;

/** Real TCP demo: intentionally close this client's socket at committed boundaries, then resume. */
public final class ResumeDemoMain {
    private ResumeDemoMain() {
    }

    private static void cut(TcpClient client) {
        try {
            client.disconnect();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static void main(String[] args) throws Exception {
        args = vn.edu.lanfileshare.common.cli.CliArguments.decode(args);
        if (args.length != 5)
            throw new IllegalArgumentException(
                    "Usage: ResumeDemoMain <sourceFile> <destination> <host> <port> <checkpointDirectory>");
        Path source = Path.of(args[0]), target = Path.of(args[1]);
        long size = Files.size(source);
        if (size < 1024 * 1024)
            throw new IllegalArgumentException("Demo source must be at least 1 MiB to interrupt before completion");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            throw new FileAlreadyExistsException(target.toString());
        try (TcpClient client = new TcpClient()) {
            client.connect(args[2], Integer.parseInt(args[3]));
            UploadService upload = new UploadService(client, 1L << 30, new UploadCheckpointStore(Path.of(args[4])));
            TransferInterruptedException uploadCut;
            try {
                upload.uploadFile(source, p -> {
                    if (p.bytesTransferred() >= size * 3 / 10 && p.percentage() < 100)
                        cut(client);
                });
                throw new IOException("Demo upload was not interrupted");
            } catch (TransferInterruptedException interrupted) {
                uploadCut = interrupted;
            }
            long uploadBefore = client.metrics().sentDataBytes();
            var uploaded = upload.resumeUpload(uploadCut.transferId(), source, p -> {
            });
            System.out.println("UPLOAD_RESUME transferId=" + uploadCut.transferId() + " fileId="
                    + uploaded.result().file().fileId() + " before=" + uploadBefore + " after="
                    + uploaded.payloadBytesThisAttempt() + " total=" + client.metrics().sentDataBytes()
                    + " resumedFrom=" + uploaded.resumedFromOffset());
            client.listFiles();
            DownloadService download = new DownloadService(client);
            TransferInterruptedException downloadCut;
            try {
                download.downloadFile(uploaded.result().file().fileId(), target, p -> {
                    if (p.bytesTransferred() >= size * 4 / 10 && p.status() == TransferStatus.TRANSFERRING)
                        cut(client);
                });
                throw new IOException("Demo download was not interrupted");
            } catch (TransferInterruptedException interrupted) {
                downloadCut = interrupted;
            }
            long downloadBefore = client.metrics().receivedDataBytes();
            var downloaded = download.resumeDownload(downloadCut.transferId(), downloadCut.checkpoint(), p -> {
            });
            client.listFiles();
            if (Files.mismatch(source, downloaded.result().destination()) != -1)
                throw new IOException("Byte mismatch");
            System.out.println("DOWNLOAD_RESUME transferId=" + downloadCut.transferId() + " before=" + downloadBefore
                    + " after=" + downloaded.payloadBytesThisAttempt() + " total="
                    + client.metrics().receivedDataBytes() + " resumedFrom=" + downloaded.resumedFromOffset());
            System.out.println("RESUME_DEMO_PASS size=" + size + " sha256=" + FileHashes.sha256(target));
        }
    }
}
