package vn.edu.lanfileshare.server.storage;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** One catalog per runtime directory. Only published files appear in list/find. */
public final class FileCatalog {
    private final Path completed;
    private final Path partial;
    private final Map<UUID, FileMetadata> files = new ConcurrentHashMap<>();

    public FileCatalog(Path runtimeDirectory) throws IOException {
        Path root = runtimeDirectory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        root = root.toRealPath();
        completed = directory(root.resolve("completed"));
        partial = directory(root.resolve("partial"));
        loadCompleted();
    }

    private static Path directory(Path path) throws IOException {
        Files.createDirectories(path);
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Storage directory must be a real directory");
        }
        return path;
    }

    public List<FileMetadata> list() {
        return files.values().stream().sorted(Comparator.comparing(FileMetadata::name)
                .thenComparing(f -> f.fileId().toString())).toList();
    }

    public Optional<FileMetadata> find(UUID fileId) {
        return Optional.ofNullable(files.get(fileId));
    }

    // The methods below are storage operations for TransferService, not network endpoints.
    public void createPart(UUID transferId) throws IOException {
        Files.createFile(part(transferId));
    }

    public long partSize(UUID transferId) throws IOException {
        return Files.size(regular(part(transferId)));
    }

    public String partHash(UUID transferId) throws IOException {
        return Sha256.of(regular(part(transferId)));
    }

    public void writePart(UUID transferId, long offset, byte[] data) throws IOException {
        Path path = regular(part(transferId));
        try (SeekableByteChannel channel = Files.newByteChannel(path,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            channel.position(offset);
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) channel.write(buffer);
        }
    }

    public void truncatePart(UUID transferId, long size) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(regular(part(transferId)),
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            channel.truncate(size);
        }
    }

    public byte[] readFile(UUID fileId, long offset, int length) throws IOException {
        byte[] data = new byte[length];
        try (SeekableByteChannel channel = Files.newByteChannel(regular(content(fileId)),
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            channel.position(offset);
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) == -1) throw new IOException("Published file was truncated");
            }
        }
        return data;
    }

    public boolean matches(FileMetadata file) throws IOException {
        Path path = regular(content(file.fileId()));
        return Files.size(path) == file.size() && Sha256.of(path).equals(file.sha256());
    }

    /** Caller has checked size/hash and holds the transfer lock. */
    public synchronized void publish(UUID transferId, FileMetadata file) throws IOException {
        Path source = regular(part(transferId));
        Path target = content(file.fileId());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || files.containsKey(file.fileId())) {
            throw new IOException("File ID already exists");
        }
        move(source, target, false);
        try {
            Properties properties = new Properties();
            properties.setProperty("fileId", file.fileId().toString());
            properties.setProperty("name", file.name());
            properties.setProperty("size", Long.toString(file.size()));
            properties.setProperty("sha256", file.sha256());
            writeProperties(completed.resolve(file.fileId() + ".properties"), properties);
        } catch (IOException failure) {
            try { move(target, source, false); } catch (IOException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
        files.put(file.fileId(), file);
    }

    public void saveTransfer(UUID transferId, Properties metadata) throws IOException {
        writeProperties(partial.resolve(transferId + ".properties"), metadata);
    }

    public void deleteTransfer(UUID transferId) throws IOException {
        Files.deleteIfExists(part(transferId));
        Files.deleteIfExists(partial.resolve(transferId + ".properties"));
    }

    /** Removes only old UUID artifacts, including abandoned parts from a previous process. */
    public void cleanupOrphans(long cutoffMillis, java.util.Set<UUID> liveTransfers) throws IOException {
        Map<UUID, List<Path>> artifacts = new HashMap<>();
        try (var paths = Files.list(partial)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if (name.startsWith("metadata-") && name.endsWith(".tmp")
                        && lastModifiedOrMissing(path) <= cutoffMillis) {
                    Files.deleteIfExists(path);
                    continue;
                }
                int dot = name.indexOf('.');
                if (dot < 0 || !(name.endsWith(".part") || name.endsWith(".properties"))) continue;
                UUID id;
                try { id = UUID.fromString(name.substring(0, dot)); }
                catch (IllegalArgumentException ignored) { continue; }
                if (!liveTransfers.contains(id)) artifacts.computeIfAbsent(id, ignored -> new ArrayList<>()).add(path);
            }
        }
        for (List<Path> group : artifacts.values()) {
            long newest = Long.MIN_VALUE;
            for (Path path : group) {
                newest = Math.max(newest, lastModifiedOrMissing(path));
            }
            if (newest <= cutoffMillis) for (Path path : group) Files.deleteIfExists(path);
        }
    }

    private static long lastModifiedOrMissing(Path path) throws IOException {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (NoSuchFileException removedConcurrently) {
            // Metadata replacement and transfer completion can remove a listed path.
            return Long.MIN_VALUE;
        }
    }

    private Path part(UUID id) { return partial.resolve(id + ".part"); }
    private Path content(UUID id) { return completed.resolve(id + ".bin"); }

    private static Path regular(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Storage file is missing or is not a regular file");
        }
        return path;
    }

    private void loadCompleted() throws IOException {
        try (var paths = Files.newDirectoryStream(completed, "*.properties")) {
            for (Path path : paths) {
                Properties p = new Properties();
                try (Reader reader = Files.newBufferedReader(regular(path), StandardCharsets.UTF_8)) {
                    p.load(reader);
                }
                try {
                    UUID id = UUID.fromString(p.getProperty("fileId"));
                    FileMetadata file = new FileMetadata(id, p.getProperty("name"),
                            Long.parseLong(p.getProperty("size")), p.getProperty("sha256"));
                    if (!path.getFileName().toString().equals(id + ".properties")
                            || Files.size(regular(content(id))) != file.size()) {
                        throw new IOException("Invalid completed file metadata");
                    }
                    files.put(id, file);
                } catch (IllegalArgumentException | NullPointerException ex) {
                    throw new IOException("Invalid completed file metadata", ex);
                }
            }
        }
    }

    private static void writeProperties(Path target, Properties properties) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "metadata-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "LAN File Sharing v1");
            }
            move(temporary, target, true);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void move(Path source, Path target, boolean replace) throws IOException {
        try {
            if (replace) Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            if (replace) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(source, target);
        }
    }
}
