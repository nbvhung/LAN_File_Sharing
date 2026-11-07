package vn.edu.lanfileshare.client.transfer;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.model.Messages.FileList;
import vn.edu.lanfileshare.common.protocol.*;

/** Local caller-owned checkpoints; default temp root never writes into Maven source directories. */
public final class UploadCheckpointStore {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    private final Path directory;

    public UploadCheckpointStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public static UploadCheckpointStore defaults() {
        return new UploadCheckpointStore(Path.of(System.getProperty("java.io.tmpdir"), "lanfs-client"));
    }

    public Path path(UUID id) {
        return directory.resolve("uploads").resolve(id + ".json");
    }

    public synchronized void save(UploadCheckpoint checkpoint) throws IOException {
        synchronized (CheckpointLocks.forPath(path(checkpoint.transferId()))) {
            saveUnlocked(checkpoint);
        }
    }

    private void saveUnlocked(UploadCheckpoint checkpoint) throws IOException {
        Path file = path(checkpoint.transferId());
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "upload-", ".tmp");
        try {
            Files.write(temporary, JSON.writeValueAsBytes(checkpoint));
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public synchronized UploadCheckpoint load(UUID id) throws IOException {
        synchronized (CheckpointLocks.forPath(path(id))) {
            return loadUnlocked(id);
        }
    }

    private UploadCheckpoint loadUnlocked(UUID id) throws IOException {
        Path file = path(id);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 16384)
            throw new ProtocolException(ErrorCode.TRANSFER_NOT_FOUND, "Upload checkpoint missing", false);
        UploadCheckpoint checkpoint;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            checkpoint = JSON.readValue(input, UploadCheckpoint.class);
        }
        if (checkpoint.schemaVersion() != 1 || !id.equals(checkpoint.transferId()) || checkpoint.sourcePath() == null
                || checkpoint.status() == null || checkpoint.nextOffset() < 0 || checkpoint.file() == null
                || checkpoint.nextOffset() > checkpoint.file().size())
            throw new ProtocolException(ErrorCode.TRANSFER_CHANGED, "Invalid upload checkpoint", false);
        ControlCodec.validate(ControlCodec.encode(MessageType.FILE_LIST,
                new FileList(1, new UUID(0, 0), List.of(checkpoint.file()))));
        if (System.currentTimeMillis() - checkpoint.lastActivityTime() >= java.time.Duration.ofHours(24).toMillis())
            throw new ProtocolException(ErrorCode.TRANSFER_NOT_FOUND, "Upload checkpoint expired", false);
        return checkpoint;
    }
}
