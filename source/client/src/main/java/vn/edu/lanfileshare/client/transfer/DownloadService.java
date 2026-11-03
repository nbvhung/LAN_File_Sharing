package vn.edu.lanfileshare.client.transfer;

import java.io.*;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import vn.edu.lanfileshare.client.network.*;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.*;

/** One framed stop-and-wait download, verified before exclusive local publication. */
public final class DownloadService {
    private final TcpClient client;
    private final long maxFileBytes;
    public DownloadService(TcpClient client) { this(client, UploadService.DEFAULT_MAX_FILE_BYTES); }
    public DownloadService(TcpClient client, long maxFileBytes) {
        if (maxFileBytes < 1) throw new IllegalArgumentException("File limit must be positive");
        this.client = Objects.requireNonNull(client); this.maxFileBytes = maxFileBytes;
    }
    /** destination is the full local filename; existing files/directories/symlinks are rejected. */
    public DownloadResult downloadFile(UUID fileId, Path destination, ProgressListener<DownloadProgress> listener) throws IOException {
        Objects.requireNonNull(fileId); Objects.requireNonNull(destination); Objects.requireNonNull(listener);
        Path target = DownloadFile.validateDestination(destination);
        return client.exchange(connection -> download(connection, fileId, target, listener));
    }
    private DownloadResult download(ClientConnection connection, UUID fileId, Path target,
                                    ProgressListener<DownloadProgress> listener) throws IOException {
        UUID requestId = UUID.randomUUID(); long started = System.nanoTime();
        DownloadFile local = null; TransferReady ready = null;
        try {
            connection.send(ControlCodec.encode(MessageType.DOWNLOAD_INIT, new DownloadInit(1, requestId, fileId)));
            Frame response = connection.receive(requestId);
            if (response.type() != MessageType.TRANSFER_READY) throw new IOException("Expected TRANSFER_READY");
            ready = ControlCodec.decode(response, TransferReady.class);
            if (ready.direction() != Direction.DOWNLOAD || !fileId.equals(ready.fileId()) || ready.nextOffset() != 0)
                throw new ProtocolException(ErrorCode.BAD_REQUEST, "Invalid download READY metadata", false);
            if (ready.size() > maxFileBytes) throw new ProtocolException(ErrorCode.FILE_TOO_LARGE, "Download exceeds configured size limit", false);
            local = new DownloadFile(target, ready, requestId);
            report(listener, 0, ready.size(), started, TransferStatus.INITIALIZED);
            acknowledge(connection, requestId, ready.transferId(), 0);
            while (local.nextOffset() < ready.size()) {
                Frame incoming = connection.receive(requestId);
                if (incoming.type() != MessageType.FILE_CHUNK) throw new IOException("Expected FILE_CHUNK");
                long offset = local.write(FileChunkCodec.decode(incoming.payload()));
                acknowledge(connection, requestId, ready.transferId(), offset);
                if (offset < ready.size()) report(listener, offset, ready.size(), started, TransferStatus.TRANSFERRING);
            }
            report(listener, ready.size(), ready.size(), started, TransferStatus.VERIFYING);
            local.verifyAndPublish();
            connection.send(ControlCodec.encode(MessageType.TRANSFER_COMPLETE,
                new TransferComplete(1, requestId, ready.transferId(), ready.fileId(), ready.size(), ready.sha256())));
            local.completed();
            report(listener, ready.size(), ready.size(), started, TransferStatus.COMPLETED);
            return new DownloadResult(ready.transferId(), new FileInfo(ready.fileId(), ready.name(), ready.size(), ready.sha256()),
                local.destination(), Duration.ofNanos(System.nanoTime() - started));
        } catch (IOException | RuntimeException ex) {
            TransferStatus status = ex instanceof EOFException || ex instanceof SocketException || ex instanceof SocketTimeoutException
                ? TransferStatus.INTERRUPTED : TransferStatus.FAILED;
            if (local != null) {
                try { local.abort(status); } catch (IOException cleanup) { ex.addSuppressed(cleanup); }
            }
            if (ready != null && !(ex instanceof ServerResponseException)) {
                ErrorCode code = ex instanceof ProtocolException protocol ? protocol.code() : ErrorCode.IO_ERROR;
                try { connection.send(ControlCodec.encode(MessageType.ERROR,
                    new ErrorMessage(1, requestId, code, "Download could not be completed", ready.transferId()))); }
                catch (IOException notify) { ex.addSuppressed(notify); }
            }
            if (ready != null) {
                try { report(listener, local == null ? 0 : local.nextOffset(), ready.size(), started, status); }
                catch (RuntimeException callback) { if (callback != ex) ex.addSuppressed(callback); }
            }
            throw ex;
        } finally { if (local != null) local.close(); }
    }
    private static void acknowledge(ClientConnection connection, UUID requestId, UUID transferId, long offset) throws IOException {
        connection.send(ControlCodec.encode(MessageType.PROGRESS_ACK, new ProgressAck(1, requestId, transferId, offset)));
    }
    private static void report(ProgressListener<DownloadProgress> listener, long bytes, long total, long started, TransferStatus status) {
        long nanos = Math.max(1, System.nanoTime() - started);
        double percentage = status == TransferStatus.COMPLETED ? 100 : total == 0 ? 0 : Math.min(99.99, bytes * 100.0 / total);
        listener.onProgress(new DownloadProgress(bytes, total, percentage, Duration.ofNanos(nanos), bytes * 1_000_000_000.0 / nanos, status));
    }
}
