package vn.edu.lanfileshare.server.storage;

import java.io.IOException;
import java.nio.file.*;

/** Server-owned runtime directories. Relative paths resolve against the process working directory. */
public record StorageConfiguration(Path root) {
    public StorageConfiguration {
        root = root.toAbsolutePath().normalize();
        rejectSource(root);
    }
    private static void rejectSource(Path path) {
        for (Path segment : path) {
            if (segment.toString().equalsIgnoreCase("source"))
                throw new IllegalArgumentException("Runtime storage must be outside source/");
        }
    }
    public Path completed() { return root.resolve("completed"); }
    public Path metadata() { return root.resolve("metadata"); }
    public Path staging() { return root.resolve("staging"); }
    public void initialize() throws IOException {
        Files.createDirectories(root);
        rejectSource(root.toRealPath());
        for (Path directory : new Path[]{completed(), metadata(), staging()}) {
            if (Files.isSymbolicLink(directory)) throw new IOException("Storage directory cannot be a symbolic link");
            Files.createDirectories(directory);
            if (!directory.toRealPath().getParent().equals(root.toRealPath()))
                throw new IOException("Storage directory escapes runtime root");
        }
    }
}
