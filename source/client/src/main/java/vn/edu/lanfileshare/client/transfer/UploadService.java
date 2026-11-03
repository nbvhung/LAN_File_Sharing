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
    public UploadService(TcpClient client) { this(client, DEFAULT_MAX_FILE_BYTES); }
    public UploadService(TcpClient client, long maxFileBytes) {
        if (maxFileBytes < 1) throw new IllegalArgumentException("File limit must be positive");
        this.client = Objects.requireNonNull(client); this.maxFileBytes = maxFileBytes;
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
            return client.exchange(connection -> upload(connection, source, init, listener));
        }
    }
    private TransferResult upload(ClientConnection connection, FileChannel source, UploadInit init,
                                  ProgressListener<UploadProgress> listener) throws IOException {
        long started = System.nanoTime();
        connection.send(ControlCodec.encode(MessageType.UPLOAD_INIT, init));
        Frame response = connection.receive(init.requestId());
        if (response.type() != MessageType.TRANSFER_READY) throw new IOException("Expected TRANSFER_READY");
        TransferReady ready = ControlCodec.decode(response, TransferReady.class);
        if (ready.direction() != Direction.UPLOAD || ready.nextOffset() != 0 || ready.size() != init.size()
            || !ready.name().equals(init.name()) || !ready.sha256().equals(init.sha256()))
            throw new IOException("TRANSFER_READY metadata mismatch");
        report(listener, 0, init.size(), started, false);
        long offset = 0;
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
            if (offset < init.size()) report(listener, offset, init.size(), started, false);
        }
        if (source.size() != init.size()) throw new IOException("Source size changed during upload");
        Frame completion = connection.receive(init.requestId());
        if (completion.type() != MessageType.TRANSFER_COMPLETE) throw new IOException("Expected TRANSFER_COMPLETE");
        TransferComplete complete = ControlCodec.decode(completion, TransferComplete.class);
        if (!complete.transferId().equals(ready.transferId()) || !complete.fileId().equals(ready.fileId())
            || complete.size() != init.size() || !complete.sha256().equals(init.sha256()))
            throw new IOException("TRANSFER_COMPLETE metadata mismatch");
        report(listener, init.size(), init.size(), started, true);
        return new TransferResult(ready.transferId(), new FileInfo(ready.fileId(), init.name(), init.size(), init.sha256()),
            Duration.ofNanos(System.nanoTime() - started));
    }
    private static void report(ProgressListener<UploadProgress> listener, long bytes, long total, long started, boolean complete) {
        long nanos = Math.max(1, System.nanoTime() - started);
        double percent = complete ? 100.0 : total == 0 ? 0 : 100.0 * bytes / total;
        listener.onProgress(new UploadProgress(bytes, total, percent, Duration.ofNanos(nanos), bytes * 1_000_000_000.0 / nanos));
    }
}
