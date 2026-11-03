package vn.edu.lanfileshare.server.transfer;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.*;

/** One open verified source, at most one unacknowledged chunk, no socket reads. */
public final class DownloadSession implements AutoCloseable {
    private final UUID transferId = UUID.randomUUID(), sessionId, requestId;
    private final FileInfo file;
    private final TransferStateStore store;
    private FileChannel source;
    private TransferState state = TransferState.INITIALIZED;
    private long nextOffset, awaitingOffset;
    private boolean started;
    public DownloadSession(UUID sessionId, UUID requestId, FileInfo file, FileChannel source, TransferStateStore store) throws IOException {
        this.sessionId = sessionId; this.requestId = requestId; this.file = file; this.source = source; this.store = store;
        try { persist(); }
        catch (IOException ex) { try { close(); } catch (IOException cleanup) { ex.addSuppressed(cleanup); } throw ex; }
    }
    public TransferSnapshot snapshot() {
        return new TransferSnapshot(1, transferId, file.fileId(), requestId, sessionId, file.name(), file.size(), file.sha256(),
            Direction.DOWNLOAD, nextOffset, state, System.currentTimeMillis());
    }
    public boolean terminal() { return state == TransferState.COMPLETED || state == TransferState.FAILED || state == TransferState.INTERRUPTED; }
    public Optional<FileChunk> acknowledge(long offset) throws IOException {
        if (terminal()) throw new ProtocolException(ErrorCode.TRANSFER_CHANGED, "Download is not active", false);
        if (offset != awaitingOffset) throw new ProtocolException(ErrorCode.OFFSET_MISMATCH, "ACK does not match the outstanding chunk", false);
        started = true; nextOffset = offset; state = TransferState.TRANSFERRING; persist();
        if (nextOffset == file.size()) { close(); return Optional.empty(); }
        if (source.size() != file.size()) throw new ProtocolException(ErrorCode.TRANSFER_CHANGED, "Download source changed", false);
        int length = (int)Math.min(FrameCodec.MAX_CHUNK_DATA, file.size() - nextOffset);
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (source.read(buffer, nextOffset + buffer.position()) == -1)
                throw new ProtocolException(ErrorCode.TRANSFER_CHANGED, "Download source became incomplete", false);
        }
        awaitingOffset = nextOffset + length;
        return Optional.of(new FileChunk(transferId, nextOffset, buffer.array()));
    }
    public void completed(TransferComplete complete) throws IOException {
        if (terminal() || !started || nextOffset != file.size() || !complete.fileId().equals(file.fileId())
            || complete.size() != file.size() || !complete.sha256().equals(file.sha256()))
            throw new ProtocolException(ErrorCode.BAD_REQUEST, "Invalid download completion", false);
        close(); state = TransferState.COMPLETED; persist();
    }
    public void failed() throws IOException {
        close(); if (state != TransferState.COMPLETED) { state = TransferState.FAILED; persist(); }
    }
    public void interrupted() throws IOException { close(); if (!terminal()) { state = TransferState.INTERRUPTED; persist(); } }
    private void persist() throws IOException { store.save(snapshot()); }
    @Override public void close() throws IOException {
        if (source != null) { FileChannel previous = source; source = null; previous.close(); }
    }
}
