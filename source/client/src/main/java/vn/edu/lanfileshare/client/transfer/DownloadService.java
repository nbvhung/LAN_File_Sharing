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
        return client.transfer(false, connection -> download(connection, fileId, target, listener, null));
    }
    public ResumeResult<DownloadResult> resumeDownload(UUID id, Path checkpoint, ProgressListener<DownloadProgress> listener) throws IOException {
        return resumeDownload(id, checkpoint, listener, ReconnectPolicy.defaults());
    }
    public ResumeResult<DownloadResult> resumeDownload(UUID id, Path checkpoint, ProgressListener<DownloadProgress> listener, ReconnectPolicy policy) throws IOException {
        Objects.requireNonNull(id); Objects.requireNonNull(checkpoint); Objects.requireNonNull(listener);
        DownloadCheckpoint saved = DownloadFile.loadCheckpoint(checkpoint, id);
        if (saved.file().size() > maxFileBytes) throw new ProtocolException(ErrorCode.FILE_TOO_LARGE, "Download exceeds configured limit", false);
        long before = client.metrics().receivedDataBytes();
        DownloadResult result = ResumeAttempts.run(client, policy, () -> client.transfer(true,
            connection -> download(connection, saved.file().fileId(), checkpoint, listener, saved)));
        return new ResumeResult<>(result, saved.nextOffset(), client.metrics().receivedDataBytes() - before);
    }
    private DownloadResult download(ClientConnection connection, UUID fileId, Path target,
                                    ProgressListener<DownloadProgress> listener, DownloadCheckpoint resumed) throws IOException {
        UUID requestId = UUID.randomUUID(); long started = System.nanoTime();
        DownloadFile local = null; TransferReady ready = null;
        try {
            if (resumed == null) connection.send(ControlCodec.encode(MessageType.DOWNLOAD_INIT, new DownloadInit(1, requestId, fileId)));
            else connection.send(ControlCodec.encode(MessageType.RESUME_QUERY, new ResumeQuery(1, requestId, resumed.transferId(), Direction.DOWNLOAD,
                resumed.file().fileId(), resumed.file().size(), resumed.file().sha256())));
            Frame response = connection.receive(requestId);
            if (response.type() != MessageType.TRANSFER_READY) throw new IOException("Expected TRANSFER_READY");
            ready = ControlCodec.decode(response, TransferReady.class);
            if (ready.direction() != Direction.DOWNLOAD || !fileId.equals(ready.fileId()) || (resumed == null && ready.nextOffset() != 0))
                throw new ProtocolException(ErrorCode.BAD_REQUEST, "Invalid download READY metadata", false);
            if (ready.size() > maxFileBytes) throw new ProtocolException(ErrorCode.FILE_TOO_LARGE, "Download exceeds configured size limit", false);
            if (resumed != null && (!ready.transferId().equals(resumed.transferId())
                || !resumed.file().equals(new FileInfo(ready.fileId(), ready.name(), ready.size(), ready.sha256()))))
                throw new ProtocolException(ErrorCode.TRANSFER_CHANGED, "Download resume READY mismatch", false);
            local = resumed == null ? new DownloadFile(target, ready, requestId) : DownloadFile.resume(target, ready, requestId);
            report(listener, local.nextOffset(), ready.size(), started, resumed == null ? TransferStatus.INITIALIZED : TransferStatus.RESUMING);
            acknowledge(connection, requestId, ready.transferId(), local.nextOffset());
            while (local.nextOffset() < ready.size()) {
                Frame incoming = connection.receive(requestId);
                if (incoming.type() != MessageType.FILE_CHUNK) throw new IOException("Expected FILE_CHUNK");
                long offset = local.write(FileChunkCodec.decode(incoming.payload()));
                // Callback after durable write, before ACK permits a deterministic cancellation at an exact receiver offset.
                if (offset < ready.size()) report(listener, offset, ready.size(), started, TransferStatus.TRANSFERRING);
                acknowledge(connection, requestId, ready.transferId(), offset);
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
            TransferStatus status = TransferFailures.interrupted(ex) ? TransferStatus.INTERRUPTED : TransferStatus.FAILED;
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
            if (status == TransferStatus.INTERRUPTED && local != null && !local.published() && ex instanceof IOException io)
                throw new TransferInterruptedException(ready.transferId(), Direction.DOWNLOAD, local.checkpointPath(), local.nextOffset(), io);
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
