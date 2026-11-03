package vn.edu.lanfileshare.client.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.UUID;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.io.FileHashes;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.*;

/** Owns one local download transaction. Server names never participate in local path construction. */
final class DownloadFile implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path destination, part, checkpoint;
    private final TransferReady ready;
    private final UUID requestId;
    private FileChannel channel;
    private long nextOffset;
    private boolean published;
    static Path validateDestination(Path requested) throws IOException {
        Path absolute = requested.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || absolute.getFileName() == null || !Files.isDirectory(parent))
            throw new IOException("Destination parent must be an existing directory");
        Path target = parent.toRealPath().resolve(absolute.getFileName());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(target.toString());
        if (!Files.isWritable(target.getParent())) throw new AccessDeniedException("Destination directory is not writable");
        return target;
    }
    DownloadFile(Path target, TransferReady ready, UUID requestId) throws IOException {
        destination = validateDestination(target); this.ready = ready; this.requestId = requestId;
        part = Files.createTempFile(destination.getParent(), ".lanfs-" + ready.transferId() + "-", ".part");
        checkpoint = part.resolveSibling(part.getFileName() + ".json");
        try {
            channel = FileChannel.open(part, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            save(TransferStatus.INITIALIZED);
        } catch (IOException ex) {
            try { close(); Files.deleteIfExists(part); Files.deleteIfExists(checkpoint); }
            catch (IOException cleanup) { ex.addSuppressed(cleanup); }
            throw ex;
        }
    }
    long nextOffset() { return nextOffset; }
    boolean published() { return published; }
    Path partPath() { return part; }
    Path destination() { return destination; }
    long write(FileChunk chunk) throws IOException {
        if (!ready.transferId().equals(chunk.transferId()))
            throw new ProtocolException(ErrorCode.TRANSFER_NOT_FOUND, "Unexpected chunk transferId", false);
        byte[] data = chunk.data();
        long end = chunk.offset() + data.length;
        if (end > ready.size() || chunk.offset() > nextOffset || (chunk.offset() < nextOffset && end > nextOffset))
            throw new ProtocolException(ErrorCode.OFFSET_MISMATCH, "Unexpected chunk offset", false);
        if (end <= nextOffset) return nextOffset;
        ByteBuffer bytes = ByteBuffer.wrap(data);
        while (bytes.hasRemaining()) channel.write(bytes);
        nextOffset = end;
        save(TransferStatus.TRANSFERRING); // Data and local metadata precede ACK.
        return nextOffset;
    }
    void verifyAndPublish() throws IOException {
        close(); save(TransferStatus.VERIFYING);
        if (nextOffset != ready.size() || Files.size(part) != ready.size() || !FileHashes.sha256(part).equals(ready.sha256()))
            throw new ProtocolException(ErrorCode.HASH_MISMATCH, "Downloaded file failed SHA-256 verification", false);
        if (!destination.getParent().toRealPath().equals(destination.getParent()))
            throw new IOException("Destination directory changed during download");
        // ATOMIC_MOVE may replace an existing target even without REPLACE_EXISTING on some providers.
        // A same-directory hard link is an atomic, exclusive publication of the verified bytes.
        try { Files.createLink(destination, part); }
        catch (UnsupportedOperationException ex) { throw new IOException("Destination filesystem must support hard-link publication", ex); }
        published = true;
        Files.delete(part);
    }
    void completed() throws IOException { Files.deleteIfExists(checkpoint); }
    void abort(TransferStatus status) throws IOException {
        close();
        if (published || status == TransferStatus.INTERRUPTED) save(status);
        else { Files.deleteIfExists(part); Files.deleteIfExists(checkpoint); }
    }
    private record Checkpoint(int schemaVersion, UUID requestId, UUID transferId, FileInfo file,
        String targetName, long nextOffset, TransferStatus status, long updatedAtEpochMillis) {}
    private void save(TransferStatus status) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), ".lanfs-checkpoint-", ".tmp");
        try {
            var state = new Checkpoint(1, requestId, ready.transferId(), new FileInfo(ready.fileId(), ready.name(), ready.size(), ready.sha256()),
                destination.getFileName().toString(), nextOffset, status, System.currentTimeMillis());
            Files.write(temporary, JSON.writeValueAsBytes(state));
            Files.move(temporary, checkpoint, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    @Override public void close() throws IOException {
        if (channel != null) { FileChannel previous = channel; channel = null; previous.close(); }
    }
}
