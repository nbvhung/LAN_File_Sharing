package vn.edu.lanfileshare.server.transfer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Clock;
import java.util.UUID;
import vn.edu.lanfileshare.common.io.PrefixHash;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.*;
import vn.edu.lanfileshare.server.storage.FileStorageService;

/** Upload identity and committed prefix persist across sockets, but not server restarts. */
public final class TransferSession implements ManagedTransfer {
    private final UUID transferId = UUID.randomUUID(), fileId = UUID.randomUUID();
    private UUID sessionId;
    private UploadInit request;
    private final TransferStateStore store;
    private final Path part;
    private final Clock clock;
    private FileChannel channel;
    private long nextOffset, lastActivity;
    private TransferState state = TransferState.INITIALIZED;
    private PrefixHash digest = PrefixHash.empty();
    private String prefix;
    public TransferSession(UUID sessionId, UploadInit request, FileStorageService storage, TransferStateStore store) throws IOException {
        this(sessionId, request, storage, store, Clock.systemUTC());
    }
    public TransferSession(UUID sessionId, UploadInit request, FileStorageService storage, TransferStateStore store, Clock clock) throws IOException {
        this.sessionId = sessionId; this.request = request; this.store = store; this.clock = clock; lastActivity = clock.millis();
        storage.configuration().initialize(); part = storage.partPath(transferId);
        channel = FileChannel.open(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try { prefix = digest.value(part, 0); persist(); }
        catch (IOException ex) { try { close(); Files.deleteIfExists(part); } catch (IOException cleanup) { ex.addSuppressed(cleanup); } throw ex; }
    }
    public synchronized TransferSnapshot snapshot() {
        return new TransferSnapshot(2, transferId, fileId, request.requestId(), sessionId, request.name(), request.size(), request.sha256(),
            Direction.UPLOAD, nextOffset, state, lastActivity, prefix);
    }
    public synchronized FileInfo file() { return new FileInfo(fileId, request.name(), request.size(), request.sha256()); }
    public synchronized boolean terminal() { return state == TransferState.COMPLETED || state == TransferState.FAILED; }
    public synchronized void append(FileChunk chunk) throws IOException {
        byte[] data = chunk.data();
        if (terminal() || state == TransferState.INTERRUPTED || !chunk.transferId().equals(transferId)
            || chunk.offset() != nextOffset || data.length > request.size() - nextOffset) throw new IllegalStateException("Invalid upload append");
        long oldOffset = nextOffset, oldTime = lastActivity; String oldPrefix = prefix; TransferState oldState = state;
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data); while (buffer.hasRemaining()) channel.write(buffer);
            digest.update(data); prefix = digest.value(part, nextOffset + data.length);
            nextOffset += data.length; state = TransferState.TRANSFERRING; lastActivity = clock.millis(); persist();
        } catch (IOException ex) {
            nextOffset = oldOffset; prefix = oldPrefix; lastActivity = oldTime; state = oldState;
            try { channel.truncate(oldOffset); channel.position(oldOffset); digest = PrefixHash.read(part, oldOffset); }
            catch (IOException cleanup) { ex.addSuppressed(cleanup); }
            throw ex;
        }
    }
    @Override public synchronized void resume(UUID owner, UUID requestId) throws IOException {
        close();
        try {
            if (!Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS) || Files.size(part) < nextOffset || Files.size(part) > request.size()) throw changed();
            PrefixHash verified = PrefixHash.read(part, nextOffset);
            if (!verified.value(part, nextOffset).equals(prefix)) throw changed();
            channel = FileChannel.open(part, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            channel.truncate(nextOffset); channel.position(nextOffset); digest = verified;
        } catch (IOException ex) {
            failed(); throw changed();
        }
        sessionId = owner; request = new UploadInit(1, requestId, request.name(), request.size(), request.sha256());
        state = nextOffset == 0 ? TransferState.INITIALIZED : TransferState.TRANSFERRING; lastActivity = clock.millis(); persist();
    }
    private static ProtocolException changed() { return new ProtocolException(ErrorCode.TRANSFER_CHANGED, "Upload partial file is missing or changed", false); }
    public synchronized void touch() throws IOException {
        if (!terminal()) { lastActivity = clock.millis(); persist(); }
    }
    public synchronized void completed() throws IOException {
        if (nextOffset != request.size() || terminal()) throw new IllegalStateException("Invalid completion state");
        close(); state = TransferState.COMPLETED; lastActivity = clock.millis(); persist();
    }
    public synchronized void failed() throws IOException {
        if (state == TransferState.COMPLETED) return;
        close(); state = TransferState.FAILED; lastActivity = clock.millis(); persist(); Files.deleteIfExists(part);
    }
    @Override public synchronized void interrupted() throws IOException {
        close();
        if (!terminal() && state != TransferState.INTERRUPTED) { state = TransferState.INTERRUPTED; lastActivity = clock.millis(); persist(); }
    }
    private void persist() throws IOException { store.save(snapshot()); }
    @Override public synchronized void close() throws IOException { if (channel != null) { FileChannel old = channel; channel = null; old.close(); } }
}
