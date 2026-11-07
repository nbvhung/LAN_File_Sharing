package vn.edu.lanfileshare.server.storage;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;
import vn.edu.lanfileshare.common.model.Messages.FileList;
import vn.edu.lanfileshare.common.protocol.*;

/** Manages UUID-addressed completed bytes. Has no dependency on sockets or client paths. */
public final class FileStorageService {
    private final StorageConfiguration configuration;

    public FileStorageService(StorageConfiguration configuration) throws IOException {
        this.configuration = configuration;
        configuration.initialize();
    }

    public StorageConfiguration configuration() {
        return configuration;
    }

    public Path completedPath(UUID fileId) {
        return configuration.completed().resolve(Objects.requireNonNull(fileId).toString());
    }

    /** Opens and verifies the same handle that a download will use; caller owns it. */
    public java.nio.channels.FileChannel openCompleted(FileInfo file) throws IOException {
        validateMetadata(file);
        configuration.initialize();
        Path path = completedPath(file.fileId());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path))
            throw new IOException("Completed source is not readable");
        var channel = java.nio.channels.FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try {
            if (channel.size() != file.size()
                    || !vn.edu.lanfileshare.common.io.FileHashes.sha256(channel).equals(file.sha256()))
                throw new ProtocolException(vn.edu.lanfileshare.common.error.ErrorCode.TRANSFER_CHANGED,
                        "Completed source changed", false);
            channel.position(0);
            return channel;
        } catch (IOException | RuntimeException ex) {
            try {
                channel.close();
            } catch (IOException cleanup) {
                ex.addSuppressed(cleanup);
            }
            throw ex;
        }
    }

    public Path partPath(UUID transferId) {
        return configuration.staging().resolve(Objects.requireNonNull(transferId) + ".part");
    }

    /** Upload finalization: no completed UUID slot exists until the part's size/hash are verified. */
    public void verifyAndPromote(UUID transferId, FileInfo expected) throws IOException {
        validateMetadata(expected);
        configuration.initialize();
        Path part = partPath(transferId);
        if (!Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS) || Files.size(part) != expected.size()
                || !vn.edu.lanfileshare.common.io.FileHashes.sha256(part).equals(expected.sha256()))
            throw new ProtocolException(vn.edu.lanfileshare.common.error.ErrorCode.HASH_MISMATCH,
                    "Upload integrity check failed", false);
        Path destination = completedPath(expected.fileId());
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Completed UUID slot already exists");
        Files.move(part, destination, StandardCopyOption.ATOMIC_MOVE);
    }

    public static void validateMetadata(FileInfo file) throws IOException {
        Objects.requireNonNull(file);
        ControlCodec
                .validate(ControlCodec.encode(MessageType.FILE_LIST, new FileList(1, new UUID(0, 0), List.of(file))));
    }

    /** Full hash verification, including same-size modifications; never follows file symlinks. */
    public boolean matches(FileInfo file) throws IOException {
        validateMetadata(file);
        configuration.initialize();
        Path path = completedPath(file.fileId());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != file.size())
            return false;
        return describe(file.fileId(), file.name()).equals(file);
    }

    private FileInfo describe(UUID id, String name) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
        long size = 0;
        try (InputStream input = Files.newInputStream(completedPath(id), LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
                size += count;
            }
        }
        return new FileInfo(id, name, size, HexFormat.of().formatHex(digest.digest()));
    }

    /** Trusted local administration only. Copies bytes before verification; does not publish metadata. */
    public FileInfo importVerified(Path localFile, String displayName) throws IOException {
        validateMetadata(new FileInfo(new UUID(0, 0), displayName, 0, "0".repeat(64)));
        if (!Files.isRegularFile(localFile, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Local input must be a regular file");
        configuration.initialize();
        UUID id = UUID.randomUUID();
        Path temporary = Files.createTempFile(configuration.staging(), id + "-", ".part");
        try {
            try (InputStream input = Files.newInputStream(localFile, LinkOption.NOFOLLOW_LINKS);
                    OutputStream output = Files.newOutputStream(temporary)) {
                input.transferTo(output);
            }
            // Move into a UUID slot, then hash the stored bytes. It remains unpublished until registration.
            Files.move(temporary, completedPath(id), StandardCopyOption.ATOMIC_MOVE);
            return describe(id, displayName);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
