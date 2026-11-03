package vn.edu.lanfileshare.server.transfer;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.ProtocolException;
import vn.edu.lanfileshare.server.storage.StorageConfiguration;

/** One synchronized registry/lease boundary shared by both directions. Never restores identities from disk. */
public final class TransferSessionManager implements AutoCloseable {
    public static final Duration RETENTION = Duration.ofHours(24);
    private static final System.Logger LOG = System.getLogger(TransferSessionManager.class.getName());
    private static final class Entry {
        final ManagedTransfer transfer; UUID owner;
        Entry(ManagedTransfer transfer) { this.transfer = transfer; owner = transfer.snapshot().sessionId(); }
    }
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final StorageConfiguration configuration;
    private final Clock clock;
    private long lastSweep = -1;
    public TransferSessionManager(StorageConfiguration configuration) { this(configuration, Clock.systemUTC()); }
    public TransferSessionManager(StorageConfiguration configuration, Clock clock) { this.configuration = configuration; this.clock = clock; }
    public Clock clock() { return clock; }
    private boolean expired(TransferSnapshot snapshot) { return clock.millis() - snapshot.updatedAtEpochMillis() >= RETENTION.toMillis(); }
    public synchronized void register(ManagedTransfer transfer) throws IOException {
        if (lastSweep < 0 || clock.millis() - lastSweep >= 60_000) purgeExpired();
        UUID id = transfer.snapshot().transferId();
        if (entries.putIfAbsent(id, new Entry(transfer)) != null) throw new IOException("Transfer ID already registered");
    }
    public synchronized boolean owns(ManagedTransfer transfer, UUID owner) {
        Entry entry = entries.get(transfer.snapshot().transferId());
        return entry != null && entry.transfer == transfer && Objects.equals(entry.owner, owner) && !expired(transfer.snapshot());
    }
    public synchronized TransferSnapshot snapshot(UUID id) throws IOException {
        Entry entry = entries.get(id);
        if (entry == null || expired(entry.transfer.snapshot())) throw missing();
        return entry.transfer.snapshot();
    }
    public synchronized ManagedTransfer claim(ResumeQuery query, UUID owner) throws IOException {
        Entry entry = entries.get(query.transferId());
        if (entry == null || expired(entry.transfer.snapshot())) {
            try { purgeExpired(); } catch (IOException ex) { LOG.log(System.Logger.Level.WARNING, "Expired transfer cleanup failed", ex); }
            throw missing();
        }
        TransferSnapshot state = entry.transfer.snapshot();
        if (state.direction() != query.direction() || !state.fileId().equals(query.fileId()) || state.size() != query.size() || !state.sha256().equals(query.sha256()))
            throw new ProtocolException(ErrorCode.TRANSFER_CHANGED, "Resume metadata does not match transfer", false);
        if (state.state() == TransferState.COMPLETED || state.state() == TransferState.FAILED)
            throw new ProtocolException(ErrorCode.TRANSFER_CHANGED, "Transfer is already terminal", false);
        if (entry.owner != null) throw new ProtocolException(ErrorCode.SERVER_BUSY, "Transfer is still owned by another connection", false);
        entry.transfer.resume(owner, query.requestId()); entry.owner = owner;
        return entry.transfer;
    }
    public synchronized void release(ManagedTransfer transfer, UUID owner) throws IOException {
        Entry entry = entries.get(transfer.snapshot().transferId());
        if (entry == null || entry.transfer != transfer || !Objects.equals(entry.owner, owner)) return;
        try { transfer.interrupted(); } finally { entry.owner = null; notifyAll(); }
    }
    public synchronized boolean awaitDetached(UUID id, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (entries.containsKey(id) && entries.get(id).owner != null) {
            long left = deadline - System.nanoTime(); if (left <= 0) return false;
            java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, left);
        }
        return true;
    }
    public synchronized int purgeExpired() throws IOException {
        lastSweep = clock.millis();
        int removed = 0; var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue(); TransferSnapshot snapshot = entry.transfer.snapshot();
            if (!expired(snapshot)) continue;
            entry.transfer.close(); deleteStaging(snapshot); iterator.remove(); removed++;
        }
        // Garbage collect only validated, expired local snapshots from earlier processes; never register them.
        configuration.initialize();
        try (var files = Files.newDirectoryStream(configuration.staging(), "*.json")) {
            for (Path file : files) {
                try {
                    String name = file.getFileName().toString(); UUID id = UUID.fromString(name.substring(0, name.length()-5));
                    if (!name.equals(id + ".json") || entries.containsKey(id) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                    TransferSnapshot snapshot = new TransferStateStore(configuration).read(id);
                    if (snapshot == null || snapshot.schemaVersion() < 1 || snapshot.schemaVersion() > 2 || !id.equals(snapshot.transferId()) || snapshot.updatedAtEpochMillis() <= 0 || snapshot.direction() == null
                        || snapshot.state() == null || snapshot.nextOffset() < 0 || snapshot.nextOffset() > snapshot.size()) continue;
                    vn.edu.lanfileshare.server.storage.FileStorageService.validateMetadata(new FileInfo(snapshot.fileId(), snapshot.name(), snapshot.size(), snapshot.sha256()));
                    if (expired(snapshot)) { deleteStaging(snapshot); removed++; }
                } catch (IOException | IllegalArgumentException ex) { LOG.log(System.Logger.Level.WARNING, "Ignoring unknown staging entry: " + file.getFileName()); }
            }
        }
        if (removed > 0) notifyAll();
        return removed;
    }
    private void deleteStaging(TransferSnapshot snapshot) throws IOException {
        if (snapshot.direction() == Direction.UPLOAD) Files.deleteIfExists(configuration.staging().resolve(snapshot.transferId() + ".part"));
        new TransferStateStore(configuration).delete(snapshot.transferId());
    }
    private static ProtocolException missing() { return new ProtocolException(ErrorCode.TRANSFER_NOT_FOUND, "Transfer not found or expired", false); }
    @Override public synchronized void close() throws IOException {
        IOException failure = null;
        for (Entry entry : entries.values()) {
            try { entry.transfer.interrupted(); } catch (IOException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
        }
        entries.clear(); notifyAll(); if (failure != null) throw failure;
    }
}
