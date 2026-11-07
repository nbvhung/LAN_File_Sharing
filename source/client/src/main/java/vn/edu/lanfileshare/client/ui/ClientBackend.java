package vn.edu.lanfileshare.client.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import vn.edu.lanfileshare.client.transfer.*;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;

/** Application boundary; no frame or stream types are exposed to Swing. */
public interface ClientBackend extends AutoCloseable {
    record Outcome(UUID transferId, FileInfo file, Path destination) {
    }

    void connect(Endpoint endpoint) throws IOException;

    void disconnect() throws IOException;

    boolean connected();

    List<FileInfo> listFiles() throws IOException;

    Outcome upload(Path source, ProgressListener<TransferProgress> progress) throws IOException;

    Outcome download(UUID id, Path target, ProgressListener<TransferProgress> progress) throws IOException;

    Outcome resume(TransferItem transfer, ProgressListener<TransferProgress> progress) throws IOException;

    /** Called from a dedicated control thread, never the EDT. */
    void interrupt() throws IOException;

    @Override
    void close() throws IOException;
}
