package vn.edu.lanfileshare.server.transfer;

import static vn.edu.lanfileshare.common.error.ErrorCode.*;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.protocol.FrameCodec;
import vn.edu.lanfileshare.server.storage.FileCatalog;
import vn.edu.lanfileshare.server.storage.FileMetadata;
import vn.edu.lanfileshare.server.storage.FileNames;

/**
 * Thread-safe v1 transfer engine. The TCP dispatcher supplies one fresh session UUID per
 * connection and calls interrupt(sessionId) in finally. No socket or JSON dependency.
 */
public final class TransferService {
    private static final Logger LOG = Logger.getLogger(TransferService.class.getName());
    public static final long DEFAULT_MAX_FILE_SIZE = 1_073_741_824L;
    public static final int DEFAULT_MAX_TRANSFERS = 128;
    public static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

    public record Ready(UUID transferId, Direction direction, UUID fileId, String name,
                        long size, String sha256, long nextOffset) { }
    public record ProgressAck(UUID transferId, long nextOffset) { }
    public record Completion(UUID transferId, UUID fileId, long size, String sha256) { }
    public record StartResult(Ready ready, Optional<Completion> completion) { }
    public record UploadProgress(ProgressAck ack, Optional<Completion> completion) { }
    public record ResumeRequest(UUID transferId, Direction direction, UUID fileId,
                                long size, String sha256) { }
    public record Snapshot(Ready transfer, TransferState state, Instant lastActivity) { }
    public record FileChunk(UUID transferId, long offset, byte[] data) {
        public FileChunk { data = data == null ? null : data.clone(); }
        @Override public byte[] data() { return data == null ? null : data.clone(); }
    }

    private final FileCatalog catalog;
    private final long maxFileSize;
    private final int maxTransfers;
    private final Duration retention;
    private final Clock clock;
    private final Object lifecycle = new Object();
    private final ConcurrentHashMap<UUID, Transfer> transfers = new ConcurrentHashMap<>();

    public TransferService(FileCatalog catalog) {
        this(catalog, DEFAULT_MAX_FILE_SIZE, DEFAULT_MAX_TRANSFERS, DEFAULT_RETENTION, Clock.systemUTC());
    }

    public TransferService(FileCatalog catalog, long maxFileSize, int maxTransfers,
                           Duration retention, Clock clock) {
        this.catalog = Objects.requireNonNull(catalog);
        if (maxFileSize < 0 || maxTransfers < 1 || retention == null
                || retention.isNegative() || retention.isZero() || retention.toMillis() < 1) {
            throw new IllegalArgumentException("Invalid transfer limits");
        }
        this.maxFileSize = maxFileSize;
        this.maxTransfers = maxTransfers;
        this.retention = retention;
        this.clock = Objects.requireNonNull(clock);
    }

    /** Immutable snapshot suitable for LIST_FILES and read-only benchmark consumers. */
    public List<FileMetadata> listFiles() { return catalog.list(); }

    public StartResult initUpload(UUID sessionId, String name, long size, String sha256)
            throws TransferException {
        requireSession(sessionId);
        try { FileNames.validate(name); }
        catch (IllegalArgumentException ex) { throw error(INVALID_NAME, "Invalid file name"); }
        validateSizeHash(size, sha256);
        Transfer transfer;
        synchronized (lifecycle) {
            ensureCapacity();
            transfer = new Transfer(UUID.randomUUID(), Direction.UPLOAD,
                    new FileMetadata(UUID.randomUUID(), name, size, sha256), sessionId);
            try {
                catalog.createPart(transfer.id);
                transfer.state = TransferState.READY;
                persist(transfer);
                transfers.put(transfer.id, transfer);
            } catch (IOException ex) {
                discard(transfer, TransferState.FAILED);
                throw io(ex);
            }
        }
        synchronized (transfer) {
            Ready ready = ready(transfer);
            Optional<Completion> complete = size == 0 ? Optional.of(finishUpload(transfer)) : Optional.empty();
            LOG.fine(() -> "Upload initialized: " + transfer.id);
            return new StartResult(ready, complete);
        }
    }

    public UploadProgress acceptUploadChunk(UUID sessionId, FileChunk chunk) throws TransferException {
        requireSession(sessionId);
        if (chunk == null || chunk.transferId() == null || chunk.data == null
                || chunk.offset() < 0 || chunk.data.length < 1
                || chunk.data.length > FrameCodec.MAX_CHUNK_DATA) {
            throw error(INVALID_FRAME, "Invalid file chunk");
        }
        Transfer transfer = find(chunk.transferId());
        synchronized (transfer) {
            owned(transfer, sessionId, Direction.UPLOAD);
            int length = chunk.data.length;
            if (length > transfer.file.size() || chunk.offset() > transfer.file.size() - length) {
                throw error(INVALID_FRAME, "Chunk exceeds declared file size");
            }
            long end = chunk.offset() + length; // bounded by size above, cannot overflow
            if (end <= transfer.nextOffset) {
                touchAndPersist(transfer);
                return new UploadProgress(new ProgressAck(transfer.id, transfer.nextOffset), Optional.empty());
            }
            if (chunk.offset() != transfer.nextOffset) {
                throw error(OFFSET_MISMATCH, "Chunk does not start at nextOffset");
            }
            long previousOffset = transfer.nextOffset;
            try {
                catalog.writePart(transfer.id, chunk.offset(), chunk.data);
                transfer.nextOffset = end;
                transfer.state = TransferState.TRANSFERRING;
                touch(transfer);
                persist(transfer); // close data file and commit metadata before returning ACK
            } catch (IOException ex) {
                transfer.nextOffset = previousOffset;
                try { catalog.truncatePart(transfer.id, previousOffset); }
                catch (IOException rollback) { ex.addSuppressed(rollback); }
                interruptedAfterIo(transfer, ex);
                throw io(ex);
            }
            ProgressAck ack = new ProgressAck(transfer.id, transfer.nextOffset);
            Optional<Completion> complete = end == transfer.file.size()
                    ? Optional.of(finishUpload(transfer)) : Optional.empty();
            return new UploadProgress(ack, complete);
        }
    }

    public Ready initDownload(UUID sessionId, UUID fileId) throws TransferException {
        requireSession(sessionId);
        if (fileId == null) throw error(BAD_REQUEST, "Missing fileId");
        FileMetadata file = catalog.find(fileId).orElseThrow(() -> error(NOT_FOUND, "File not found"));
        verifySource(file);
        synchronized (lifecycle) {
            ensureCapacity();
            Transfer transfer = new Transfer(UUID.randomUUID(), Direction.DOWNLOAD, file, sessionId);
            transfer.state = TransferState.READY;
            transfer.awaitingInitialAck = true;
            try { persist(transfer); }
            catch (IOException ex) { discard(transfer, TransferState.FAILED); throw io(ex); }
            transfers.put(transfer.id, transfer);
            return ready(transfer);
        }
    }

    /**
     * No download bytes are read before the first ACK. Later ACKs must confirm the
     * single pending chunk; a repeated ACK retransmits that chunk without advancing.
     */
    public Optional<FileChunk> acknowledgeDownload(UUID sessionId, UUID transferId, long nextOffset)
            throws TransferException {
        requireSession(sessionId);
        Transfer transfer = find(transferId);
        synchronized (transfer) {
            owned(transfer, sessionId, Direction.DOWNLOAD);
            if (nextOffset < 0 || nextOffset > transfer.file.size()) {
                throw error(OFFSET_MISMATCH, "Download ACK is outside the file");
            }
            if (transfer.awaitingInitialAck) {
                if (!transfer.resuming && nextOffset != 0) {
                    throw error(OFFSET_MISMATCH, "New download must start at offset zero");
                }
                transfer.nextOffset = nextOffset;
                transfer.sentEnd = nextOffset;
                transfer.awaitingInitialAck = false;
            } else if (transfer.pending != null) {
                if (nextOffset == transfer.nextOffset) {
                    touchAndPersist(transfer);
                    return Optional.of(transfer.pending);
                }
                if (nextOffset != transfer.sentEnd) {
                    throw error(OFFSET_MISMATCH, "ACK does not confirm the pending chunk");
                }
                transfer.nextOffset = nextOffset;
                transfer.pending = null;
            } else if (nextOffset != transfer.nextOffset) {
                throw error(OFFSET_MISMATCH, "Unexpected download ACK");
            }
            transfer.state = TransferState.TRANSFERRING;
            touchAndPersist(transfer);
            if (nextOffset == transfer.file.size()) return Optional.empty();
            int length = (int) Math.min(FrameCodec.MAX_CHUNK_DATA, transfer.file.size() - nextOffset);
            try {
                byte[] data = catalog.readFile(transfer.file.fileId(), nextOffset, length);
                transfer.pending = new FileChunk(transfer.id, nextOffset, data);
                transfer.sentEnd = nextOffset + length;
                return Optional.of(transfer.pending);
            } catch (IOException ex) {
                interruptedAfterIo(transfer, ex);
                throw io(ex);
            }
        }
    }

    /** Client sends this only after checking hash and publishing its downloaded file. */
    public void completeDownload(UUID sessionId, Completion completion) throws TransferException {
        requireSession(sessionId);
        if (completion == null) throw error(BAD_REQUEST, "Missing completion");
        Transfer transfer = find(completion.transferId());
        synchronized (transfer) {
            owned(transfer, sessionId, Direction.DOWNLOAD);
            if (!transfer.file.fileId().equals(completion.fileId()) || transfer.file.size() != completion.size()
                    || !transfer.file.sha256().equals(completion.sha256())) {
                throw error(TRANSFER_CHANGED, "Completion metadata does not match");
            }
            // Completion may replace the last ACK, including for an empty file.
            if (transfer.sentEnd != transfer.file.size()) {
                throw error(OFFSET_MISMATCH, "Download has not reached the end of the file");
            }
            try { verifySource(transfer.file); }
            catch (TransferException ex) { discard(transfer, TransferState.FAILED); throw ex; }
            transfer.nextOffset = transfer.file.size();
            discard(transfer, TransferState.COMPLETED);
            LOG.fine(() -> "Download completed: " + transfer.id);
        }
    }

    public StartResult resume(UUID sessionId, ResumeRequest request) throws TransferException {
        requireSession(sessionId);
        if (request == null || request.direction() == null || request.fileId() == null) {
            throw error(BAD_REQUEST, "Missing resume metadata");
        }
        validateSizeHash(request.size(), request.sha256());
        Transfer transfer = find(request.transferId());
        synchronized (transfer) {
            live(transfer);
            if (transfer.direction != request.direction() || !transfer.file.fileId().equals(request.fileId())
                    || transfer.file.size() != request.size() || !transfer.file.sha256().equals(request.sha256())) {
                throw error(TRANSFER_CHANGED, "Resume metadata does not match");
            }
            if (transfer.owner != null && !transfer.owner.equals(sessionId)) {
                throw error(SERVER_BUSY, "Transfer is attached to another connection");
            }
            try {
                if (transfer.direction == Direction.UPLOAD) {
                    if (catalog.partSize(transfer.id) < transfer.nextOffset) {
                        discard(transfer, TransferState.FAILED);
                        throw error(TRANSFER_CHANGED, "Partial file is shorter than its saved offset");
                    }
                    // Bytes beyond the committed offset were never acknowledged.
                    catalog.truncatePart(transfer.id, transfer.nextOffset);
                } else {
                    verifySource(transfer.file);
                }
                transfer.owner = sessionId;
                transfer.state = TransferState.READY;
                transfer.resuming = true;
                transfer.awaitingInitialAck = transfer.direction == Direction.DOWNLOAD;
                transfer.pending = null;
                transfer.sentEnd = transfer.nextOffset;
                touch(transfer);
                persist(transfer);
            } catch (IOException ex) {
                interruptedAfterIo(transfer, ex);
                throw io(ex);
            } catch (TransferException ex) {
                discard(transfer, TransferState.FAILED);
                throw ex;
            }
            Ready ready = ready(transfer);
            Optional<Completion> complete = transfer.direction == Direction.UPLOAD
                    && transfer.nextOffset == transfer.file.size()
                    ? Optional.of(finishUpload(transfer)) : Optional.empty();
            return new StartResult(ready, complete);
        }
    }

    /** Invoke on EOF, timeout, transport I/O error or socket close, including shutdown. */
    public void interrupt(UUID sessionId) throws TransferException {
        requireSession(sessionId);
        IOException failure = null;
        for (Transfer transfer : transfers.values()) {
            synchronized (transfer) {
                if (!sessionId.equals(transfer.owner)) continue;
                transfer.owner = null;
                transfer.pending = null;
                transfer.state = TransferState.INTERRUPTED;
                touch(transfer);
                try { persist(transfer); }
                catch (IOException ex) {
                    if (failure == null) failure = ex; else failure.addSuppressed(ex);
                }
            }
        }
        if (failure != null) throw io(failure);
    }

    /** Client ERROR (e.g. HASH_MISMATCH) terminates the owned transfer without echoing ERROR. */
    public void abort(UUID sessionId, UUID transferId) throws TransferException {
        requireSession(sessionId);
        Transfer transfer = find(transferId);
        synchronized (transfer) {
            live(transfer);
            if (!sessionId.equals(transfer.owner)) throw error(SERVER_BUSY, "Transfer is not owned by this connection");
            discard(transfer, TransferState.FAILED);
        }
    }

    public Snapshot snapshot(UUID transferId) throws TransferException {
        Transfer transfer = find(transferId);
        synchronized (transfer) {
            live(transfer);
            return new Snapshot(ready(transfer), transfer.state, transfer.lastActivity);
        }
    }

    /** Call periodically (e.g. every minute); init also invokes this before the capacity check. */
    public int cleanupExpired() throws TransferException {
        synchronized (lifecycle) {
            int removed = 0;
            for (Transfer transfer : transfers.values()) {
                synchronized (transfer) {
                    if (expired(transfer)) {
                        discard(transfer, TransferState.FAILED);
                        removed++;
                    }
                }
            }
            try { catalog.cleanupOrphans(clock.instant().minus(retention).toEpochMilli(), transfers.keySet()); }
            catch (IOException ex) { throw io(ex); }
            return removed;
        }
    }

    private Completion finishUpload(Transfer transfer) throws TransferException {
        try {
            if (catalog.partSize(transfer.id) != transfer.file.size()
                    || !catalog.partHash(transfer.id).equals(transfer.file.sha256())) {
                discard(transfer, TransferState.FAILED);
                throw error(HASH_MISMATCH, "Uploaded file does not match SHA-256");
            }
            catalog.publish(transfer.id, transfer.file);
            discard(transfer, TransferState.COMPLETED);
            LOG.fine(() -> "Upload completed: " + transfer.id);
            return new Completion(transfer.id, transfer.file.fileId(), transfer.file.size(), transfer.file.sha256());
        } catch (IOException ex) {
            interruptedAfterIo(transfer, ex);
            throw io(ex);
        }
    }

    private void verifySource(FileMetadata file) throws TransferException {
        try {
            if (catalog.matches(file)) return;
        } catch (IOException ex) {
            throw new TransferException(TRANSFER_CHANGED, "Published source file is unavailable or changed", ex);
        }
        throw error(TRANSFER_CHANGED, "Published source file changed");
    }

    private void ensureCapacity() throws TransferException {
        cleanupExpired();
        if (transfers.size() >= maxTransfers) throw error(SERVER_BUSY, "Too many unfinished transfers");
    }

    private void validateSizeHash(long size, String sha256) throws TransferException {
        if (size < 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw error(BAD_REQUEST, "Invalid size or SHA-256");
        }
        if (size > maxFileSize) throw error(FILE_TOO_LARGE, "File exceeds configured size limit");
    }

    private static void requireSession(UUID sessionId) throws TransferException {
        if (sessionId == null) throw error(BAD_REQUEST, "Missing connection session ID");
    }

    private Transfer find(UUID id) throws TransferException {
        if (id == null) throw error(BAD_REQUEST, "Missing transferId");
        Transfer transfer = transfers.get(id);
        if (transfer == null) throw error(TRANSFER_NOT_FOUND, "Transfer not found or already finished");
        return transfer;
    }

    private void live(Transfer transfer) throws TransferException {
        if (transfers.get(transfer.id) != transfer) throw error(TRANSFER_NOT_FOUND, "Transfer is no longer active");
        if (expired(transfer)) {
            discard(transfer, TransferState.FAILED);
            throw error(TRANSFER_NOT_FOUND, "Transfer expired");
        }
    }

    private void owned(Transfer transfer, UUID sessionId, Direction direction) throws TransferException {
        live(transfer);
        if (transfer.direction != direction) throw error(BAD_REQUEST, "Wrong transfer direction");
        if (!sessionId.equals(transfer.owner)) throw error(SERVER_BUSY, "Transfer is not owned by this connection");
    }

    private boolean expired(Transfer transfer) {
        return !clock.instant().isBefore(transfer.lastActivity.plus(retention));
    }

    private void touch(Transfer transfer) { transfer.lastActivity = clock.instant(); }

    private void touchAndPersist(Transfer transfer) throws TransferException {
        touch(transfer);
        try { persist(transfer); }
        catch (IOException ex) { interruptedAfterIo(transfer, ex); throw io(ex); }
    }

    private void persist(Transfer transfer) throws IOException {
        Properties metadata = new Properties();
        metadata.setProperty("protocolVersion", "1");
        metadata.setProperty("transferId", transfer.id.toString());
        metadata.setProperty("direction", transfer.direction.name());
        metadata.setProperty("fileId", transfer.file.fileId().toString());
        metadata.setProperty("name", transfer.file.name());
        metadata.setProperty("size", Long.toString(transfer.file.size()));
        metadata.setProperty("sha256", transfer.file.sha256());
        metadata.setProperty("nextOffset", Long.toString(transfer.nextOffset));
        metadata.setProperty("state", transfer.state.name());
        metadata.setProperty("lastActivity", transfer.lastActivity.toString());
        catalog.saveTransfer(transfer.id, metadata);
    }

    private void interruptedAfterIo(Transfer transfer, IOException cause) {
        transfer.state = TransferState.INTERRUPTED;
        transfer.owner = null;
        transfer.pending = null;
        touch(transfer);
        try { persist(transfer); } catch (IOException ex) { cause.addSuppressed(ex); }
    }

    private void discard(Transfer transfer, TransferState state) {
        transfer.state = state;
        transfer.owner = null;
        transfer.pending = null;
        transfers.remove(transfer.id, transfer);
        try { catalog.deleteTransfer(transfer.id); }
        catch (IOException ex) { LOG.log(Level.WARNING, "Could not clean transfer " + transfer.id, ex); }
    }

    private static Ready ready(Transfer transfer) {
        return new Ready(transfer.id, transfer.direction, transfer.file.fileId(), transfer.file.name(),
                transfer.file.size(), transfer.file.sha256(), transfer.nextOffset);
    }

    private static TransferException error(ErrorCode code, String message) {
        return new TransferException(code, message);
    }

    private static TransferException io(IOException cause) {
        return new TransferException(IO_ERROR, "Storage operation failed", cause);
    }

    private final class Transfer {
        final UUID id;
        final Direction direction;
        final FileMetadata file;
        UUID owner;
        TransferState state = TransferState.INIT;
        long nextOffset;
        long sentEnd;
        boolean awaitingInitialAck;
        boolean resuming;
        FileChunk pending;
        Instant lastActivity = clock.instant();

        Transfer(UUID id, Direction direction, FileMetadata file, UUID owner) {
            this.id = id;
            this.direction = direction;
            this.file = file;
            this.owner = owner;
        }
    }
}
