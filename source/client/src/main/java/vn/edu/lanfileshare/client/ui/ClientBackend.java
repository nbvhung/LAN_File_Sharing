package vn.edu.lanfileshare.client.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import vn.edu.lanfileshare.client.transfer.ProgressListener;
import vn.edu.lanfileshare.client.transfer.TransferProgress;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;

/** Các thao tác TCP và truyền file mà controller gọi từ luồng nền. */
public interface ClientBackend extends AutoCloseable {
    record Outcome(UUID transferId, FileInfo file, Path destination) {
    }

    void connect(Endpoint endpoint) throws IOException;

    void disconnect() throws IOException;

    boolean connected();

    List<FileInfo> listFiles() throws IOException;

    Outcome upload(Path source, ProgressListener<TransferProgress> progress) throws IOException;

    Outcome download(UUID fileId, Path destination, ProgressListener<TransferProgress> progress) throws IOException;

    Outcome resume(TransferItem transfer, ProgressListener<TransferProgress> progress) throws IOException;

    /** Luồng điều khiển gọi để ngắt kết nối ngay cả khi luồng truyền đang chờ dữ liệu. */
    void interrupt() throws IOException;

    @Override
    void close() throws IOException;
}
