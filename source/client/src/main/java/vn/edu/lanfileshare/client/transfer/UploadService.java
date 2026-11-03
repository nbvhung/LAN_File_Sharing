package vn.edu.lanfileshare.client.transfer;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import vn.edu.lanfileshare.client.network.*;
import vn.edu.lanfileshare.common.io.FileHashes;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.*;

/** Stop-and-wait v1 upload. One open source handle and one 64 KiB chunk buffer. */
public final class UploadService {
    public static final long DEFAULT_MAX_FILE_BYTES = 1L << 30;
    private final TcpClient client;
    private final long maxFileBytes;
    private final UploadCheckpointStore checkpoints;
    public UploadCheckpointStore checkpoints() { return checkpoints; }
    public UploadService(TcpClient client) { this(client, DEFAULT_MAX_FILE_BYTES); }
    public UploadService(TcpClient client, long maxFileBytes) {
        this(client, maxFileBytes, UploadCheckpointStore.defaults());
    }
    public UploadService(TcpClient client, long maxFileBytes, UploadCheckpointStore checkpoints) {
        if (maxFileBytes < 1) throw new IllegalArgumentException("File limit must be positive");
        this.client = Objects.requireNonNull(client); this.maxFileBytes = maxFileBytes; this.checkpoints = Objects.requireNonNull(checkpoints);
    }
    public TransferResult uploadFile(Path localFile, ProgressListener<UploadProgress> listener) throws IOException {
        Objects.requireNonNull(localFile); Objects.requireNonNull(listener);
        if (!Files.isRegularFile(localFile, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(localFile))
            throw new IOException("Upload source must exist and be a readable regular file");
        try (FileChannel source = FileChannel.open(localFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = source.size();
            if (size > maxFileBytes) throw new IOException("Source exceeds configured file size limit");
            String name = localFile.getFileName().toString();
            MessageValidator.validate(ControlCodec.encode(MessageType.UPLOAD_INIT,
                new UploadInit(1, new UUID(0, 0), name, size, "0".repeat(64))));
            String hash = FileHashes.sha256(source);
            if (source.position() != size || source.size() != size) throw new IOException("Source changed while hashing");
            source.position(0);
            UUID requestId = UUID.randomUUID();
            UploadInit init = new UploadInit(1, requestId, name, size, hash);
            return client.transfer(false, connection -> upload(connection, source, localFile, init, listener, null));
        }
    }
    public ResumeResult<TransferResult> resumeUpload(UUID id, ProgressListener<UploadProgress> listener) throws IOException {
        UploadCheckpoint checkpoint = checkpoints.load(id); return resumeUpload(id, Path.of(checkpoint.sourcePath()), listener);
    }
    public ResumeResult<TransferResult> resumeUpload(UUID id, Path localFile, ProgressListener<UploadProgress> listener) throws IOException {
        return resumeUpload(id, localFile, listener, ReconnectPolicy.defaults());
    }
    public ResumeResult<TransferResult> resumeUpload(UUID id, Path localFile, ProgressListener<UploadProgress> listener, ReconnectPolicy policy) throws IOException {
        UploadCheckpoint checkpoint = checkpoints.load(id); Objects.requireNonNull(listener);
        if (checkpoint.status() == TransferStatus.COMPLETED || checkpoint.status() == TransferStatus.FAILED)
            throw new ProtocolException(vn.edu.lanfileshare.common.error.ErrorCode.TRANSFER_CHANGED, "Upload checkpoint is terminal", false);
        if (!Files.isRegularFile(localFile, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(localFile))
            throw new ProtocolException(vn.edu.lanfileshare.common.error.ErrorCode.TRANSFER_CHANGED, "Upload source missing or changed", false);
        try (FileChannel source = FileChannel.open(localFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (source.size() > maxFileBytes || source.size() != checkpoint.file().size() || !FileHashes.sha256(source).equals(checkpoint.file().sha256()))
                throw new ProtocolException(vn.edu.lanfileshare.common.error.ErrorCode.TRANSFER_CHANGED, "Upload source changed", false);
            long before = client.metrics().sentDataBytes();
            final long[] offset = {-1};
            TransferResult result = ResumeAttempts.run(client, policy, () -> client.transfer(true, connection -> {
                UploadInit init = new UploadInit(1, UUID.randomUUID(), checkpoint.file().name(), checkpoint.file().size(), checkpoint.file().sha256());
                return upload(connection, source, localFile, init, progress -> { if (offset[0] < 0) offset[0] = progress.bytesTransferred(); listener.onProgress(progress); }, checkpoint);
            }));
            return new ResumeResult<>(result, offset[0], client.metrics().sentDataBytes() - before);
        }
    }
    private TransferResult upload(ClientConnection connection, FileChannel source, Path localFile, UploadInit init,
                                  ProgressListener<UploadProgress> listener, UploadCheckpoint resumed) throws IOException {
        UploadCheckpoint active = null;
        try {
        long started = System.nanoTime();
        if (resumed == null) connection.send(ControlCodec.encode(MessageType.UPLOAD_INIT, init));
        else connection.send(ControlCodec.encode(MessageType.RESUME_QUERY, new ResumeQuery(1, init.requestId(), resumed.transferId(),
            Direction.UPLOAD, resumed.file().fileId(), init.size(), init.sha256())));
        Frame response = connection.receive(init.requestId());
        if (response.type() != MessageType.TRANSFER_READY) throw new IOException("Expected TRANSFER_READY");
        TransferReady ready = ControlCodec.decode(response, TransferReady.class);
        if (ready.direction() != Direction.UPLOAD || (resumed == null && ready.nextOffset() != 0) || ready.size() != init.size()
            || !ready.name().equals(init.name()) || !ready.sha256().equals(init.sha256()))
            throw new IOException("TRANSFER_READY metadata mismatch");
        if (resumed != null && (!ready.transferId().equals(resumed.transferId()) || !ready.fileId().equals(resumed.file().fileId())))
            throw new IOException("Resume READY identity mismatch");
        long offset = ready.nextOffset(); source.position(offset);
        active = new UploadCheckpoint(1, ready.transferId(), new FileInfo(ready.fileId(), init.name(), init.size(), init.sha256()),
            localFile.toAbsolutePath().normalize().toString(), offset, TransferStatus.TRANSFERRING, System.currentTimeMillis());
        checkpoints.save(active);
        report(listener, offset, init.size(), started, false);
        ByteBuffer buffer = ByteBuffer.allocate(FrameCodec.MAX_CHUNK_DATA);
        while (offset < init.size()) {
            int count = (int)Math.min(buffer.capacity(), init.size() - offset);
            buffer.clear().limit(count);
            while (buffer.hasRemaining()) {
                if (source.read(buffer) == -1) throw new EOFException("Source shrank during upload");
            }
            byte[] data = Arrays.copyOf(buffer.array(), count);
            connection.send(new Frame(MessageType.FILE_CHUNK, FileChunkCodec.encode(new FileChunk(ready.transferId(), offset, data))));
            Frame ackFrame = connection.receive(init.requestId());
            if (ackFrame.type() != MessageType.PROGRESS_ACK) throw new IOException("Expected PROGRESS_ACK");
            ProgressAck ack = ControlCodec.decode(ackFrame, ProgressAck.class);
            if (!ack.transferId().equals(ready.transferId()) || ack.nextOffset() != offset + count)
                throw new IOException("Invalid ACK transferId or nextOffset");
            offset = ack.nextOffset();
            active = new UploadCheckpoint(1, active.transferId(), active.file(), active.sourcePath(), offset, TransferStatus.TRANSFERRING, System.currentTimeMillis());
            checkpoints.save(active);
            if (offset < init.size()) report(listener, offset, init.size(), started, false);
        }
        if (source.size() != init.size()) throw new IOException("Source size changed during upload");
        Frame completion = connection.receive(init.requestId());
        if (completion.type() != MessageType.TRANSFER_COMPLETE) throw new IOException("Expected TRANSFER_COMPLETE");
        TransferComplete complete = ControlCodec.decode(completion, TransferComplete.class);
        if (!complete.transferId().equals(ready.transferId()) || !complete.fileId().equals(ready.fileId())
            || complete.size() != init.size() || !complete.sha256().equals(init.sha256()))
            throw new IOException("TRANSFER_COMPLETE metadata mismatch");
        active = new UploadCheckpoint(1, active.transferId(), active.file(), active.sourcePath(), init.size(), TransferStatus.COMPLETED, System.currentTimeMillis());
        checkpoints.save(active);
        report(listener, init.size(), init.size(), started, true);
        return new TransferResult(ready.transferId(), new FileInfo(ready.fileId(), init.name(), init.size(), init.sha256()),
            Duration.ofNanos(System.nanoTime() - started));
        } catch (IOException | RuntimeException ex) {
            if (active != null) {
                boolean interrupted = TransferFailures.interrupted(ex);
                UploadCheckpoint saved = new UploadCheckpoint(1, active.transferId(), active.file(), active.sourcePath(), active.nextOffset(),
                    active.status() == TransferStatus.COMPLETED ? TransferStatus.COMPLETED : interrupted ? TransferStatus.INTERRUPTED : TransferStatus.FAILED, System.currentTimeMillis());
                try { checkpoints.save(saved); } catch (IOException metadata) { ex.addSuppressed(metadata); }
                if (interrupted && ex instanceof IOException io)
                    throw new TransferInterruptedException(active.transferId(), Direction.UPLOAD, checkpoints.path(active.transferId()), active.nextOffset(), io);
            }
            throw ex;
        }
    }
    private static void report(ProgressListener<UploadProgress> listener, long bytes, long total, long started, boolean complete) {
        long nanos = Math.max(1, System.nanoTime() - started);
        double percent = complete ? 100.0 : total == 0 ? 0 : Math.min(99.99, 100.0 * bytes / total);
        listener.onProgress(new UploadProgress(bytes, total, percent, Duration.ofNanos(nanos), bytes * 1_000_000_000.0 / nanos));
    }
}
