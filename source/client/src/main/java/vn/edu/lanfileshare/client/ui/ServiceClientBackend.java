package vn.edu.lanfileshare.client.ui;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import vn.edu.lanfileshare.client.network.TcpClient;
import vn.edu.lanfileshare.client.transfer.*;
import vn.edu.lanfileshare.common.model.Messages.*;
/** Thin adapter to the existing Phase 1-5 services, with one sequential socket. */
public final class ServiceClientBackend implements ClientBackend {
    private final TcpClient client;
    private final UploadService uploads;
    private final DownloadService downloads;
    public ServiceClientBackend(Path checkpoints) { this(new TcpClient(), checkpoints); }
    public ServiceClientBackend(TcpClient client, Path checkpoints) {
        this.client = client;
        uploads = new UploadService(client, UploadService.DEFAULT_MAX_FILE_BYTES, new UploadCheckpointStore(checkpoints));
        downloads = new DownloadService(client);
    }
    public void connect(Endpoint endpoint) throws IOException { client.disconnect(); client.connect(endpoint.host(), endpoint.port()); }
    public void disconnect() throws IOException { client.disconnect(); }
    public boolean connected() { return client.isConnected(); }
    public List<FileInfo> listFiles() throws IOException { return client.listFiles().files(); }
    public Outcome upload(Path source, ProgressListener<TransferProgress> listener) throws IOException {
        TransferResult result = uploads.uploadFile(source, listener::onProgress);
        return new Outcome(result.transferId(), result.file(), source);
    }
    public Outcome download(UUID id, Path target, ProgressListener<TransferProgress> listener) throws IOException {
        DownloadResult result = downloads.downloadFile(id, target, listener::onProgress);
        return new Outcome(result.transferId(), result.file(), result.destination());
    }
    public Outcome resume(TransferItem item, ProgressListener<TransferProgress> listener) throws IOException {
        if (item.direction() == Direction.UPLOAD) {
            var result = uploads.resumeUpload(item.transferId(), item.localPath(), listener::onProgress).result();
            return new Outcome(result.transferId(), result.file(), item.localPath());
        }
        var result = downloads.resumeDownload(item.transferId(), item.checkpoint(), listener::onProgress).result();
        return new Outcome(result.transferId(), result.file(), result.destination());
    }
    public void interrupt() throws IOException { client.interruptConnection(); }
    public void close() throws IOException { client.close(); }
}
