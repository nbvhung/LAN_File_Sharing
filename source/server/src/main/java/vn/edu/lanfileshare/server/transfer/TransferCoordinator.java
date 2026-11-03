package vn.edu.lanfileshare.server.transfer;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.model.Messages.Direction;
import vn.edu.lanfileshare.common.protocol.*;
import vn.edu.lanfileshare.server.network.*;
import vn.edu.lanfileshare.server.storage.*;

/** Routes transfer frames while enforcing one active upload OR download per TCP connection. */
public final class TransferCoordinator implements TransferService, AutoCloseable {
    private final UploadHandler uploads;
    private final DownloadHandler downloads;
    private final TransferSessionManager manager;
    private final java.util.concurrent.ScheduledExecutorService janitor;
    private final Map<UUID, Direction> owners = new ConcurrentHashMap<>();
    public TransferCoordinator(FileStorageService storage, JsonFileCatalog catalog, long maxUploadBytes) {
        this(storage, catalog, maxUploadBytes, java.time.Clock.systemUTC());
    }
    public TransferCoordinator(FileStorageService storage, JsonFileCatalog catalog, long maxUploadBytes, java.time.Clock clock) {
        manager = new TransferSessionManager(storage.configuration(), clock);
        uploads = new UploadHandler(storage, catalog, maxUploadBytes, manager);
        downloads = new DownloadHandler(storage, catalog, manager);
        janitor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> { Thread thread = new Thread(task, "lanfs-transfer-expiry"); thread.setDaemon(true); return thread; });
        janitor.scheduleWithFixedDelay(() -> {
            try { manager.purgeExpired(); }
            catch (IOException ex) { System.getLogger(TransferCoordinator.class.getName()).log(System.Logger.Level.WARNING, "Transfer expiry cleanup failed", ex); }
        }, 1, 1, java.util.concurrent.TimeUnit.MINUTES);
    }
    @Override public void handle(SessionContext session, Frame frame) throws IOException {
        switch (frame.type()) {
            case UPLOAD_INIT, DOWNLOAD_INIT -> {
                if (uploads.hasActive(session.id()) || downloads.hasActive(session.id())) {
                    RequestDispatcher.error(session, ControlCodec.requestId(frame), ErrorCode.SERVER_BUSY, "A transfer is already active on this connection"); return;
                }
                Direction direction = frame.type() == MessageType.UPLOAD_INIT ? Direction.UPLOAD : Direction.DOWNLOAD;
                owners.put(session.id(), direction);
                if (direction == Direction.UPLOAD) uploads.handle(session, frame); else downloads.handle(session, frame);
            }
            case RESUME_QUERY -> {
                if (uploads.hasActive(session.id()) || downloads.hasActive(session.id())) {
                    RequestDispatcher.error(session, ControlCodec.requestId(frame), ErrorCode.SERVER_BUSY, "Connection already has an active transfer"); return;
                }
                var query = ControlCodec.decode(frame, vn.edu.lanfileshare.common.model.Messages.ResumeQuery.class);
                ManagedTransfer transfer = manager.claim(query, session.id()); owners.put(session.id(), query.direction());
                if (transfer instanceof TransferSession upload) uploads.resume(session, upload);
                else downloads.resume(session, (DownloadSession)transfer);
            }
            case FILE_CHUNK -> {
                if (owners.get(session.id()) == Direction.DOWNLOAD)
                    throw new ProtocolException(ErrorCode.BAD_REQUEST, "Client cannot send chunks during download", false);
                uploads.handle(session, frame);
            }
            case PROGRESS_ACK, TRANSFER_COMPLETE, ERROR -> downloads.handle(session, frame);
            default -> RequestDispatcher.error(session, ControlCodec.requestId(frame), ErrorCode.BAD_REQUEST, "Unsupported transfer message");
        }
    }
    public TransferSessionManager sessions() { return manager; }
    @Override public Optional<UUID> requestId(UUID sessionId) {
        return owners.get(sessionId) == Direction.DOWNLOAD ? downloads.requestId(sessionId) : uploads.requestId(sessionId);
    }
    @Override public void disconnected(UUID sessionId) { owners.remove(sessionId); try { uploads.disconnected(sessionId); } finally { downloads.disconnected(sessionId); } }
    @Override public void close() throws IOException {
        janitor.shutdownNow();
        IOException failure = null;
        try { uploads.close(); } catch (IOException ex) { failure = ex; }
        try { downloads.close(); } catch (IOException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
        try { manager.close(); } catch (IOException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
        if (failure != null) throw failure;
    }
}
