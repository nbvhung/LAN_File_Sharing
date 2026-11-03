package vn.edu.lanfileshare.server.transfer;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.*;
import vn.edu.lanfileshare.server.network.SessionContext;
import vn.edu.lanfileshare.server.storage.*;

/** Upload dispatch and stop-and-wait business rules. No resume/download handlers in Phase 3. */
public final class UploadHandler implements TransferService, AutoCloseable {
    public static final long DEFAULT_MAX_FILE_BYTES = 1L << 30;
    private static final System.Logger LOG = System.getLogger(UploadHandler.class.getName());
    private final FileStorageService storage;
    private final JsonFileCatalog catalog;
    private final TransferStateStore states;
    private final long maxFileBytes;
    private final Map<UUID, TransferSession> sessions = new ConcurrentHashMap<>();
    public UploadHandler(FileStorageService storage, JsonFileCatalog catalog) { this(storage, catalog, DEFAULT_MAX_FILE_BYTES); }
    public UploadHandler(FileStorageService storage, JsonFileCatalog catalog, long maxFileBytes) {
        if (maxFileBytes < 1) throw new IllegalArgumentException("File limit must be positive");
        this.storage = storage; this.catalog = catalog; this.maxFileBytes = maxFileBytes;
        states = new TransferStateStore(storage.configuration());
    }
    public boolean hasActive(UUID sessionId) {
        TransferSession transfer = sessions.get(sessionId);
        return transfer != null && !transfer.terminal();
    }
    @Override public Optional<UUID> requestId(UUID sessionId) {
        TransferSession transfer = sessions.get(sessionId);
        return transfer == null ? Optional.empty() : Optional.of(transfer.snapshot().requestId());
    }
    @Override public void handle(SessionContext session, Frame frame) throws IOException {
        switch (frame.type()) {
            case UPLOAD_INIT -> initialize(session, ControlCodec.decode(frame, UploadInit.class));
            case FILE_CHUNK -> chunk(session, FileChunkCodec.decode(frame.payload()));
            default -> error(session, ControlCodec.requestId(frame), null, ErrorCode.BAD_REQUEST, "Only upload is available in this phase");
        }
    }
    private void initialize(SessionContext session, UploadInit request) throws IOException {
        TransferSession previous = sessions.get(session.id());
        if (previous != null && !previous.terminal()) {
            error(session, request.requestId(), null, ErrorCode.SERVER_BUSY, "An upload is already active on this connection"); return;
        }
        if (request.size() > maxFileBytes) {
            error(session, request.requestId(), null, ErrorCode.FILE_TOO_LARGE, "File exceeds configured server limit"); return;
        }
        TransferSession transfer = new TransferSession(session.id(), request, storage, states);
        sessions.put(session.id(), transfer);
        TransferSnapshot state = transfer.snapshot();
        session.send(ControlCodec.encode(MessageType.TRANSFER_READY, new TransferReady(1, state.requestId(), state.transferId(),
            Direction.UPLOAD, state.fileId(), state.name(), state.size(), state.sha256(), 0)));
        if (state.size() == 0) finish(session, transfer);
    }
    private void chunk(SessionContext session, FileChunk chunk) throws IOException {
        TransferSession transfer = sessions.get(session.id());
        if (transfer == null) throw new ProtocolException(ErrorCode.INVALID_FRAME, "FILE_CHUNK requires an initialized upload", true);
        TransferSnapshot state = transfer.snapshot();
        if (!state.transferId().equals(chunk.transferId())) {
            error(session, state.requestId(), chunk.transferId(), ErrorCode.TRANSFER_NOT_FOUND, "Transfer does not belong to this connection"); return;
        }
        long end = chunk.offset() + chunk.data().length;
        if (end > state.size() || chunk.offset() > state.nextOffset()
            || (chunk.offset() < state.nextOffset() && end > state.nextOffset())) {
            error(session, state.requestId(), state.transferId(), ErrorCode.OFFSET_MISMATCH, "Chunk offset does not match committed progress"); return;
        }
        if (state.state() == TransferState.FAILED || state.state() == TransferState.INTERRUPTED) {
            error(session, state.requestId(), state.transferId(), ErrorCode.TRANSFER_CHANGED, "Transfer is no longer active"); return;
        }
        if (end <= state.nextOffset()) { acknowledge(session, state); return; }
        transfer.append(chunk);
        TransferSnapshot progress = transfer.snapshot();
        acknowledge(session, progress);
        if (progress.nextOffset() == progress.size()) finish(session, transfer);
    }
    private void finish(SessionContext session, TransferSession transfer) throws IOException {
        transfer.close();
        TransferSnapshot state = transfer.snapshot();
        try { storage.verifyAndPromote(state.transferId(), transfer.file()); }
        catch (ProtocolException ex) {
            if (ex.code() != ErrorCode.HASH_MISMATCH) throw ex;
            transfer.failed();
            error(session, state.requestId(), state.transferId(), ErrorCode.HASH_MISMATCH, "Uploaded bytes do not match SHA-256"); return;
        }
        catalog.registerCompletedFile(transfer.file());
        transfer.completed();
        session.send(ControlCodec.encode(MessageType.TRANSFER_COMPLETE,
            new TransferComplete(1, state.requestId(), state.transferId(), state.fileId(), state.size(), state.sha256())));
    }
    private static void acknowledge(SessionContext session, TransferSnapshot state) throws IOException {
        session.send(ControlCodec.encode(MessageType.PROGRESS_ACK, new ProgressAck(1, state.requestId(), state.transferId(), state.nextOffset())));
    }
    private static void error(SessionContext session, UUID requestId, UUID transferId, ErrorCode code, String message) throws IOException {
        session.send(ControlCodec.encode(MessageType.ERROR, new ErrorMessage(1, requestId, code, message, transferId)));
    }
    @Override public void disconnected(UUID sessionId) {
        TransferSession transfer = sessions.remove(sessionId);
        if (transfer != null) {
            try { transfer.interrupted(); }
            catch (IOException ex) { LOG.log(System.Logger.Level.ERROR, "Cannot persist interrupted upload", ex); }
        }
    }
    @Override public void close() throws IOException {
        IOException failure = null;
        for (TransferSession transfer : sessions.values()) {
            try { transfer.interrupted(); }
            catch (IOException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
        }
        sessions.clear();
        if (failure != null) throw failure;
    }
}
