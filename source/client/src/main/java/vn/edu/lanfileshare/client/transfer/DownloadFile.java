package vn.edu.lanfileshare.client.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.UUID;
import java.util.List;
import com.fasterxml.jackson.databind.DeserializationFeature;
import vn.edu.lanfileshare.common.io.PrefixHash;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.io.FileHashes;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.*;

/** Owns one local download transaction. Server names never participate in local path construction. */
final class DownloadFile implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    private final Path destination, part, checkpoint;
    private final TransferReady ready;
    private final UUID requestId;
    private FileChannel channel;
    private long nextOffset;
    private boolean published;
    private PrefixHash digest = PrefixHash.empty();
    private String prefix;

    static Path validateDestination(Path requested) throws IOException {
        Path absolute = requested.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || absolute.getFileName() == null || !Files.isDirectory(parent))
            throw new IOException("Destination parent must be an existing directory");
        Path target = parent.toRealPath().resolve(absolute.getFileName());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            throw new FileAlreadyExistsException(target.toString());
        if (!Files.isWritable(target.getParent()))
            throw new AccessDeniedException("Destination directory is not writable");
        return target;
    }

    DownloadFile(Path target, TransferReady ready, UUID requestId) throws IOException {
        destination = validateDestination(target);
        this.ready = ready;
        this.requestId = requestId;
        part = Files.createTempFile(destination.getParent(), ".lanfs-" + ready.transferId() + "-", ".part");
        checkpoint = part.resolveSibling(part.getFileName() + ".json");
        try {
            channel = FileChannel.open(part, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            prefix = digest.value(part, 0);
            save(TransferStatus.INITIALIZED);
        } catch (IOException ex) {
            try {
                close();
                Files.deleteIfExists(part);
                Files.deleteIfExists(checkpoint);
            } catch (IOException cleanup) {
                ex.addSuppressed(cleanup);
            }
            throw ex;
        }
    }

    static DownloadCheckpoint loadCheckpoint(Path path, UUID id) throws IOException {
        synchronized (CheckpointLocks.forPath(path)) {
            return loadUnlocked(path, id);
        }
    }

    private static DownloadCheckpoint loadUnlocked(Path path, UUID id) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16384)
            throw changed("Download checkpoint is missing");
        DownloadCheckpoint state;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            state = JSON.readValue(input, DownloadCheckpoint.class);
        }
        String name = path.getFileName().toString();
        if (state.schemaVersion() != 2 || !id.equals(state.transferId()) || state.file() == null
                || state.requestId() == null || state.status() == null || state.nextOffset() < 0
                || state.nextOffset() > state.file().size() || state.prefixSha256() == null
                || !state.prefixSha256().matches("[0-9a-f]{64}") || !name.startsWith(".lanfs-" + id + "-")
                || !name.endsWith(".part.json"))
            throw changed("Invalid download checkpoint");
        if (state.status() == TransferStatus.COMPLETED || state.status() == TransferStatus.FAILED)
            throw changed("Download checkpoint is terminal");
        if (System.currentTimeMillis() - state.updatedAtEpochMillis() >= java.time.Duration.ofHours(24).toMillis())
            throw new ProtocolException(ErrorCode.TRANSFER_NOT_FOUND, "Download checkpoint expired", false);
        ControlCodec.validate(
                ControlCodec.encode(MessageType.FILE_LIST, new FileList(1, new UUID(0, 0), List.of(state.file()))));
        Path parent = path.toAbsolutePath().normalize().getParent().toRealPath();
        if (state.targetName() == null
                || !Path.of(state.targetName()).getFileName().toString().equals(state.targetName())
                || !parent.equals(parent.resolve(state.targetName()).normalize().getParent()))
            throw changed("Invalid local target name");
        validateDestination(parent.resolve(state.targetName()));
        Path part = partFromCheckpoint(path);
        if (!Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS) || Files.size(part) < state.nextOffset()
                || Files.size(part) > state.file().size())
            throw changed("Download partial file is missing or incomplete");
        if (!PrefixHash.read(part, state.nextOffset()).value(part, state.nextOffset()).equals(state.prefixSha256()))
            throw changed("Download partial prefix has changed");
        return state;
    }

    private static Path partFromCheckpoint(Path checkpoint) {
        String name = checkpoint.getFileName().toString();
        return checkpoint.resolveSibling(name.substring(0, name.length() - 5));
    }

    static DownloadFile resume(Path checkpoint, TransferReady ready, UUID requestId) throws IOException {
        DownloadCheckpoint state = loadCheckpoint(checkpoint, ready.transferId());
        if (!state.file().equals(new FileInfo(ready.fileId(), ready.name(), ready.size(), ready.sha256())))
            throw changed("Download resume metadata changed");
        return new DownloadFile(checkpoint, state, ready, requestId);
    }

    private DownloadFile(Path checkpoint, DownloadCheckpoint state, TransferReady ready, UUID requestId)
            throws IOException {
        this.checkpoint = checkpoint.toAbsolutePath().normalize();
        this.part = partFromCheckpoint(this.checkpoint);
        this.ready = ready;
        this.requestId = requestId;
        destination = validateDestination(this.checkpoint.getParent().resolve(state.targetName()));
        nextOffset = state.nextOffset();
        prefix = state.prefixSha256();
        digest = PrefixHash.read(part, nextOffset);
        channel = FileChannel.open(part, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            channel.truncate(nextOffset);
            channel.position(nextOffset);
            save(TransferStatus.RESUMING);
        } catch (IOException ex) {
            try {
                close();
            } catch (IOException cleanup) {
                ex.addSuppressed(cleanup);
            }
            throw ex;
        }
    }

    private static ProtocolException changed(String message) {
        return new ProtocolException(ErrorCode.TRANSFER_CHANGED, message, false);
    }

    Path checkpointPath() {
        return checkpoint;
    }

    long nextOffset() {
        return nextOffset;
    }

    boolean published() {
        return published;
    }

    Path partPath() {
        return part;
    }

    Path destination() {
        return destination;
    }

    long write(FileChunk chunk) throws IOException {
        if (!ready.transferId().equals(chunk.transferId()))
            throw new ProtocolException(ErrorCode.TRANSFER_NOT_FOUND, "Unexpected chunk transferId", false);
        byte[] data = chunk.data();
        long end = chunk.offset() + data.length;
        if (end > ready.size() || chunk.offset() > nextOffset || (chunk.offset() < nextOffset && end > nextOffset))
            throw new ProtocolException(ErrorCode.OFFSET_MISMATCH, "Unexpected chunk offset", false);
        if (end <= nextOffset)
            return nextOffset;
        ByteBuffer bytes = ByteBuffer.wrap(data);
        while (bytes.hasRemaining())
            channel.write(bytes);
        digest.update(data);
        nextOffset = end;
        prefix = digest.value(part, nextOffset);
        save(TransferStatus.TRANSFERRING); // Data and local metadata precede ACK.
        return nextOffset;
    }

    void verifyAndPublish() throws IOException {
        close();
        save(TransferStatus.VERIFYING);
        if (nextOffset != ready.size() || Files.size(part) != ready.size()
                || !FileHashes.sha256(part).equals(ready.sha256()))
            throw new ProtocolException(ErrorCode.HASH_MISMATCH, "Downloaded file failed SHA-256 verification", false);
        if (!destination.getParent().toRealPath().equals(destination.getParent()))
            throw new IOException("Destination directory changed during download");
        // ATOMIC_MOVE may replace an existing target even without REPLACE_EXISTING on some providers.
        // A same-directory hard link is an atomic, exclusive publication of the verified bytes.
        try {
            Files.createLink(destination, part);
        } catch (UnsupportedOperationException ex) {
            throw new IOException("Destination filesystem must support hard-link publication", ex);
        }
        published = true;
        Files.delete(part);
    }

    void completed() throws IOException {
        Files.deleteIfExists(checkpoint);
    }

    void abort(TransferStatus status) throws IOException {
        close();
        if (published || status == TransferStatus.INTERRUPTED)
            save(status);
        else {
            Files.deleteIfExists(part);
            Files.deleteIfExists(checkpoint);
        }
    }

    private void save(TransferStatus status) throws IOException {
        synchronized (CheckpointLocks.forPath(checkpoint)) {
            saveUnlocked(status);
        }
    }

    private void saveUnlocked(TransferStatus status) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), ".lanfs-checkpoint-", ".tmp");
        try {
            var state = new DownloadCheckpoint(2, requestId, ready.transferId(),
                    new FileInfo(ready.fileId(), ready.name(), ready.size(), ready.sha256()),
                    destination.getFileName().toString(), nextOffset, status, System.currentTimeMillis(), prefix);
            Files.write(temporary, JSON.writeValueAsBytes(state));
            Files.move(temporary, checkpoint, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void close() throws IOException {
        if (channel != null) {
            FileChannel previous = channel;
            channel = null;
            previous.close();
        }
    }
}
