package vn.edu.lanfileshare.server.storage;

import java.util.Objects;
import java.util.UUID;

/** Immutable, public catalog entry; never contains a server filesystem path. */
public record FileMetadata(UUID fileId, String name, long size, String sha256) {
    public FileMetadata {
        Objects.requireNonNull(fileId, "fileId");
        FileNames.validate(name);
        if (size < 0) throw new IllegalArgumentException("Negative file size");
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid SHA-256");
        }
    }
}
