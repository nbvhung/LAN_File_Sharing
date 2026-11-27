package vn.edu.lanfileshare.client.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import vn.edu.lanfileshare.client.network.TcpClient;
import vn.edu.lanfileshare.client.transfer.DownloadResult;
import vn.edu.lanfileshare.client.transfer.DownloadService;
import vn.edu.lanfileshare.client.transfer.ProgressListener;
import vn.edu.lanfileshare.client.transfer.TransferProgress;
import vn.edu.lanfileshare.client.transfer.TransferResult;
import vn.edu.lanfileshare.client.transfer.UploadCheckpointStore;
import vn.edu.lanfileshare.client.transfer.UploadService;
import vn.edu.lanfileshare.common.model.Messages.Direction;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;

/** Dùng chung một kết nối TCP để lấy danh sách file, upload, download và resume. */
public final class ServiceClientBackend implements ClientBackend {
    private final TcpClient tcpClient;
    private final UploadService uploadService;
    private final DownloadService downloadService;

    public ServiceClientBackend(Path checkpoints) {
        this(new TcpClient(), checkpoints);
    }

    public ServiceClientBackend(TcpClient tcpClient, Path checkpoints) {
        this.tcpClient = tcpClient;
        uploadService = new UploadService(tcpClient, UploadService.DEFAULT_MAX_FILE_BYTES,
                new UploadCheckpointStore(checkpoints));
        downloadService = new DownloadService(tcpClient);
    }

    @Override
    public void connect(Endpoint endpoint) throws IOException {
        // Đóng phiên cũ trước khi dùng cùng TcpClient để mở phiên mới.
        tcpClient.disconnect();
        tcpClient.connect(endpoint.host(), endpoint.port());
    }

    @Override
    public void disconnect() throws IOException {
        tcpClient.disconnect();
    }

    @Override
    public boolean connected() {
        return tcpClient.isConnected();
    }

    @Override
    public List<FileInfo> listFiles() throws IOException {
        return tcpClient.listFiles().files();
    }

    @Override
    public Outcome upload(Path source, ProgressListener<TransferProgress> listener) throws IOException {
        TransferResult result = uploadService.uploadFile(source, listener::onProgress);
        return new Outcome(result.transferId(), result.file(), source);
    }

    @Override
    public Outcome download(UUID fileId, Path destination, ProgressListener<TransferProgress> listener) throws IOException {
        DownloadResult result = downloadService.downloadFile(fileId, destination, listener::onProgress);
        return new Outcome(result.transferId(), result.file(), result.destination());
    }

    @Override
    public Outcome resume(TransferItem item, ProgressListener<TransferProgress> listener) throws IOException {
        // Hai hướng đều tra phiên bằng transfer ID. Download dùng checkpoint để tìm file tải dở.
        if (item.direction() == Direction.UPLOAD) {
            TransferResult result = uploadService.resumeUpload(item.transferId(), item.localPath(), listener::onProgress)
                    .result();
            return new Outcome(result.transferId(), result.file(), item.localPath());
        }
        DownloadResult result = downloadService.resumeDownload(item.transferId(), item.checkpoint(), listener::onProgress)
                .result();
        return new Outcome(result.transferId(), result.file(), result.destination());
    }

    @Override
    public void interrupt() throws IOException {
        // Đóng socket để worker thoát khỏi I/O đang chờ và lưu được checkpoint.
        tcpClient.interruptConnection();
    }

    @Override
    public void close() throws IOException {
        tcpClient.close();
    }
}
