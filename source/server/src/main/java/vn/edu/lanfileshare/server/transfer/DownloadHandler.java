package vn.edu.lanfileshare.server.transfer;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.*;
import vn.edu.lanfileshare.server.network.SessionContext;
import vn.edu.lanfileshare.server.storage.*;

/** Sends chunks only in response to an exact ACK; consumes client COMPLETE/ERROR without extra wire replies. */
public final class DownloadHandler implements TransferService, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(DownloadHandler.class.getName());
    private final FileStorageService storage;
    private final FileCatalog catalog;
    private final TransferStateStore states;
    private final TransferSessionManager manager;
    private final boolean ownsManager;
    private final Map<UUID, DownloadSession> sessions = new ConcurrentHashMap<>();
    public DownloadHandler(FileStorageService storage, FileCatalog catalog) {
        this(storage, catalog, new TransferSessionManager(storage.configuration()), true);
    }
    public DownloadHandler(FileStorageService storage, FileCatalog catalog, TransferSessionManager manager) { this(storage, catalog, manager, false); }
    private DownloadHandler(FileStorageService storage, FileCatalog catalog, TransferSessionManager manager, boolean ownsManager) {
        this.storage = storage; this.catalog = catalog; this.manager = manager; this.ownsManager = ownsManager;
        states = new TransferStateStore(storage.configuration());
    }
    public boolean hasActive(UUID sessionId) { DownloadSession session = sessions.get(sessionId); return session != null && !session.terminal() && manager.owns(session, sessionId); }
    @Override public Optional<UUID> requestId(UUID sessionId) {
        DownloadSession session = sessions.get(sessionId);
        return session == null ? Optional.empty() : Optional.of(session.snapshot().requestId());
    }
    @Override public void handle(SessionContext session, Frame frame) throws IOException {
        if (frame.type() == MessageType.DOWNLOAD_INIT) { initialize(session, ControlCodec.decode(frame, DownloadInit.class)); return; }
        DownloadSession transfer = sessions.get(session.id());
        if (transfer == null) { error(session, ControlCodec.requestId(frame), null, ErrorCode.TRANSFER_NOT_FOUND, "No download on this connection"); return; }
        TransferSnapshot state = transfer.snapshot();
        if (!manager.owns(transfer, session.id())) { error(session, state.requestId(), state.transferId(), ErrorCode.TRANSFER_NOT_FOUND, "Transfer expired or is owned elsewhere"); return; }
        UUID request, id;
        switch (frame.type()) {
            case PROGRESS_ACK -> { var ack = ControlCodec.decode(frame, ProgressAck.class); request = ack.requestId(); id = ack.transferId(); }
            case TRANSFER_COMPLETE -> { var complete = ControlCodec.decode(frame, TransferComplete.class); request = complete.requestId(); id = complete.transferId(); }
            case ERROR -> { var failure = ControlCodec.decode(frame, ErrorMessage.class); request = failure.requestId(); id = failure.transferId() == null ? state.transferId() : failure.transferId(); }
            default -> { error(session, state.requestId(), state.transferId(), ErrorCode.BAD_REQUEST, "Unsupported download control"); return; }
        }
        if (!state.transferId().equals(id)) { error(session, state.requestId(), id, ErrorCode.TRANSFER_NOT_FOUND, "Transfer does not belong to this connection"); return; }
        if (!state.requestId().equals(request)) { error(session, state.requestId(), id, ErrorCode.BAD_REQUEST, "Download requestId mismatch"); return; }
        try {
            switch (frame.type()) {
                case PROGRESS_ACK -> {
                    var chunk = transfer.acknowledge(ControlCodec.decode(frame, ProgressAck.class).nextOffset());
                    if (chunk.isPresent()) session.send(new Frame(MessageType.FILE_CHUNK, FileChunkCodec.encode(chunk.get())));
                }
                case TRANSFER_COMPLETE -> transfer.completed(ControlCodec.decode(frame, TransferComplete.class));
                case ERROR -> {
                    if (ControlCodec.decode(frame, ErrorMessage.class).code() == ErrorCode.IO_ERROR) manager.release(transfer, session.id());
                    else transfer.failed();
                    LOG.log(System.Logger.Level.WARNING, "Client reported download failure: " + ControlCodec.decode(frame, ErrorMessage.class).code());
                }
                default -> throw new IllegalStateException("Unexpected routed message");
            }
        } catch (ProtocolException ex) {
            if (ex.code() == ErrorCode.TRANSFER_CHANGED) transfer.failed();
            throw ex;
        }
    }
    private void initialize(SessionContext session, DownloadInit request) throws IOException {
        if (hasActive(session.id())) { error(session, request.requestId(), null, ErrorCode.SERVER_BUSY, "A download is already active"); return; }
        Optional<FileInfo> found = catalog.findById(request.fileId());
        if (found.isEmpty()) { error(session, request.requestId(), null, ErrorCode.NOT_FOUND, "Completed file not found"); return; }
        FileInfo file = found.get();
        DownloadSession transfer = new DownloadSession(session.id(), request.requestId(), file, storage.openCompleted(file), states, storage, manager.clock());
        try { manager.register(transfer); }
        catch (IOException ex) { try { transfer.close(); } catch (IOException cleanup) { ex.addSuppressed(cleanup); } throw ex; }
        sessions.put(session.id(), transfer);
        TransferSnapshot state = transfer.snapshot();
        session.send(ControlCodec.encode(MessageType.TRANSFER_READY, new TransferReady(1, state.requestId(), state.transferId(),
            Direction.DOWNLOAD, file.fileId(), file.name(), file.size(), file.sha256(), 0)));
    }
    public void resume(SessionContext session, DownloadSession transfer) throws IOException {
        sessions.put(session.id(), transfer); TransferSnapshot state = transfer.snapshot();
        session.send(ControlCodec.encode(MessageType.TRANSFER_READY, new TransferReady(1, state.requestId(), state.transferId(),
            Direction.DOWNLOAD, state.fileId(), state.name(), state.size(), state.sha256(), state.nextOffset())));
        // Absolutely no chunk until the new connection's first ACK supplies receiver's durable offset.
    }
    private static void error(SessionContext session, UUID requestId, UUID transferId, ErrorCode code, String message) throws IOException {
        session.send(ControlCodec.encode(MessageType.ERROR, new ErrorMessage(1, requestId, code, message, transferId)));
    }
    @Override public void disconnected(UUID sessionId) {
        DownloadSession transfer = sessions.remove(sessionId);
        if (transfer != null) {
            try { manager.release(transfer, sessionId); } catch (IOException ex) { LOG.log(System.Logger.Level.ERROR, "Cannot persist interrupted download", ex); }
        }
    }
    @Override public void close() throws IOException {
        IOException failure = null;
        for (var entry : sessions.entrySet()) {
            try { manager.release(entry.getValue(), entry.getKey()); }
            catch (IOException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
        }
        sessions.clear();
        if (ownsManager) { try { manager.close(); } catch (IOException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); } }
        if (failure != null) throw failure;
    }
}
