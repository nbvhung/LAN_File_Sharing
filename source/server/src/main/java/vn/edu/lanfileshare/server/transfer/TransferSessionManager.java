package vn.edu.lanfileshare.server.transfer;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.ProtocolException;
import vn.edu.lanfileshare.server.storage.StorageConfiguration;

/** Per-transfer lease locks; slow resume verification never holds a global registry lock. */
public final class TransferSessionManager implements AutoCloseable {
    public static final Duration RETENTION = Duration.ofHours(24);
    private static final System.Logger LOG = System.getLogger(TransferSessionManager.class.getName());
    private static final class Entry {
        final ManagedTransfer transfer; UUID owner;
        Entry(ManagedTransfer transfer) { this.transfer = transfer; owner = transfer.snapshot().sessionId(); }
    }
    private final Map<UUID, Entry> entries = new java.util.concurrent.ConcurrentHashMap<>();
    private final StorageConfiguration configuration;
    private final Clock clock;
    private volatile long lastSweep = -1;
    private volatile boolean closed;
    private final Object registry = new Object();
    public TransferSessionManager(StorageConfiguration configuration) { this(configuration, Clock.systemUTC()); }
    public TransferSessionManager(StorageConfiguration configuration, Clock clock) { this.configuration = configuration; this.clock = clock; }
    public Clock clock() { return clock; }
    private boolean expired(TransferSnapshot snapshot) { return clock.millis() - snapshot.updatedAtEpochMillis() >= RETENTION.toMillis(); }
    public void register(ManagedTransfer transfer) throws IOException {
        UUID id = transfer.snapshot().transferId();
        synchronized (registry) {
            if (closed)
                throw new IOException("Transfer manager closed");
        if (entries.putIfAbsent(id, new Entry(transfer)) != null) throw new IOException("Transfer ID already registered");
    }
        // Periodic janitor handles disk sweeps, never perform a global sweep on INIT.
    }

    public boolean owns(ManagedTransfer transfer, UUID owner) {
        Entry entry = entries.get(transfer.snapshot().transferId());
        if (entry == null)
            return false;
        synchronized (entry) {
            return !closed && entries.get(transfer.snapshot().transferId()) == entry && entry.transfer == transfer && Objects.equals(entry.owner, owner) && !expired(transfer.snapshot());
    }
    }

    public TransferSnapshot snapshot(UUID id) throws IOException {
        Entry entry = entries.get(id);
        if (entry == null)
            throw missing();
        synchronized (entry) {
            if (closed || entries.get(id) != entry || expired(entry.transfer.snapshot())) throw missing();
        return entry.transfer.snapshot();
    }
    }

    public ManagedTransfer claim(ResumeQuery query, UUID owner) throws IOException {
        Entry entry = entries.get(query.transferId());
        if (entry == null)
            throw missing();
        synchronized (entry) {
            if (closed || entries.get(query.transferId()) != entry || expired(entry.transfer.snapshot()))
                throw missing();
            TransferSnapshot state = entry.transfer.snapshot();
        if (state.direction() != query.direction() || !state.fileId().equals(query.fileId()) || state.size() != query.size() || !state.sha256().equals(query.sha256()))
            throw new ProtocolException(ErrorCode.TRANSFER_CHANGED, "Resume metadata does not match transfer", false);
        if (state.state() == TransferState.COMPLETED || state.state() == TransferState.FAILED)
            throw new ProtocolException(ErrorCode.TRANSFER_CHANGED, "Transfer is already terminal", false);
        if (entry.owner != null) throw new ProtocolException(ErrorCode.SERVER_BUSY, "Transfer is still owned by another connection", false);
        entry.transfer.resume(owner, query.requestId()); entry.owner = owner;
        return entry.transfer;
    }
    }

    public void release(ManagedTransfer transfer, UUID owner) throws IOException {
        Entry entry = entries.get(transfer.snapshot().transferId());
        if (entry == null)
            return;
        synchronized (entry) {
            if (entry.transfer != transfer || !Objects.equals(entry.owner, owner)) return;
        try { transfer.interrupted(); } finally { entry.owner = null;
                entry.notifyAll(); }
    }
    }

    public boolean awaitDetached(UUID id, Duration timeout) throws InterruptedException {
        Entry entry = entries.get(id);
        if (entry == null)
            return true;
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (entry) {
            while (entries.get(id) == entry && entry.owner != null) {
            long left = deadline - System.nanoTime(); if (left <= 0) return false;
            java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(entry, left);
            }
        }
        return true;
    }
    public synchronized int purgeExpired() throws IOException {
        lastSweep = clock.millis();
        int removed = 0; var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            var item = iterator.next();
            Entry entry = item.getValue();
            synchronized (entry) {
                TransferSnapshot snapshot = entry.transfer.snapshot();
            if (!expired(snapshot)) continue;
            entry.transfer.close(); deleteStaging(snapshot);
                entries.remove(item.getKey(), entry);
                entry.notifyAll(); removed++;
            }
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

        return removed;
    }
    private void deleteStaging(TransferSnapshot snapshot) throws IOException {
        if (snapshot.direction() == Direction.UPLOAD) Files.deleteIfExists(configuration.staging().resolve(snapshot.transferId() + ".part"));
        new TransferStateStore(configuration).delete(snapshot.transferId());
    }
    private static ProtocolException missing() { return new ProtocolException(ErrorCode.TRANSFER_NOT_FOUND, "Transfer not found or expired", false); }
    @Override public void close() throws IOException {
        java.util.List<Entry> detached;
        synchronized (registry) {
            closed = true;
            detached = java.util.List.copyOf(entries.values());
            entries.clear();
        }
        IOException failure = null;
        for (Entry entry : detached)
            synchronized (entry) {
            try { entry.transfer.interrupted(); } catch (IOException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); } finally {
                    entry.owner = null;
                    entry.notifyAll();
                }
            }
        if (failure != null) throw failure;
    }
}
