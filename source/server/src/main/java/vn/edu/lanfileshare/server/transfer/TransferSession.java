package vn.edu.lanfileshare.server.transfer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.UUID;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.FileChunk;
import vn.edu.lanfileshare.server.storage.FileStorageService;

/** A single socket's upload; owns its open .part channel and durable progress snapshot. */
public final class TransferSession implements AutoCloseable {
    private final UUID transferId = UUID.randomUUID();
    private final UUID fileId = UUID.randomUUID();
    private final UUID sessionId;
    private final UploadInit request;
    private final TransferStateStore store;
    private final Path part;
    private FileChannel channel;
    private long nextOffset;
    private TransferState state = TransferState.INITIALIZED;
    public TransferSession(UUID sessionId, UploadInit request, FileStorageService storage, TransferStateStore store) throws IOException {
        this.sessionId = sessionId; this.request = request; this.store = store;
        storage.configuration().initialize();
        part = storage.partPath(transferId);
        channel = FileChannel.open(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try { persist(); }
        catch (IOException ex) {
            try { close(); Files.deleteIfExists(part); } catch (IOException cleanup) { ex.addSuppressed(cleanup); }
            throw ex;
        }
    }
    public TransferSnapshot snapshot() {
        return new TransferSnapshot(1, transferId, fileId, request.requestId(), sessionId, request.name(), request.size(),
            request.sha256(), Direction.UPLOAD, nextOffset, state, System.currentTimeMillis());
    }
    public FileInfo file() { return new FileInfo(fileId, request.name(), request.size(), request.sha256()); }
    public boolean terminal() { return state == TransferState.COMPLETED || state == TransferState.FAILED; }
    /** Caller has checked identity and bounds. FileChannel writes complete before metadata is committed. */
    public void append(FileChunk chunk) throws IOException {
        if (terminal() || state == TransferState.INTERRUPTED || chunk.offset() != nextOffset)
            throw new IllegalStateException("Upload cannot accept this chunk");
        ByteBuffer buffer = ByteBuffer.wrap(chunk.data());
        while (buffer.hasRemaining()) channel.write(buffer);
        nextOffset += buffer.limit();
        state = TransferState.TRANSFERRING;
        persist();
    }
    public void completed() throws IOException {
        if (nextOffset != request.size() || terminal()) throw new IllegalStateException("Invalid completion state");
        close(); state = TransferState.COMPLETED; persist();
    }
    public void failed() throws IOException {
        if (state == TransferState.COMPLETED) return;
        close(); state = TransferState.FAILED; persist();
        Files.deleteIfExists(part);
    }
    public void interrupted() throws IOException {
        close();
        if (!terminal()) { state = TransferState.INTERRUPTED; persist(); }
    }
    private void persist() throws IOException { store.save(snapshot()); }
    @Override public void close() throws IOException {
        if (channel != null) { FileChannel previous = channel; channel = null; previous.close(); }
    }
}
