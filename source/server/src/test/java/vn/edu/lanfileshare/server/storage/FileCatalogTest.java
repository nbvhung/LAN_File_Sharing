package vn.edu.lanfileshare.server.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCatalogTest {
    @TempDir Path root;

    @Test void looseFilesAreNotAutomaticallyPublished() throws Exception {
        FileCatalog catalog = new FileCatalog(root);
        Files.write(root.resolve("completed").resolve(UUID.randomUUID() + ".bin"), new byte[]{1});
        catalog.createPart(UUID.randomUUID());
        assertTrue(new FileCatalog(root).list().isEmpty());
    }

    @Test void malformedMetadataFailsClosed() throws Exception {
        new FileCatalog(root);
        Files.writeString(root.resolve("completed").resolve("invalid.properties"),
                "fileId=../../escape\nname=bad\nsize=0\nsha256=bad\n");
        assertThrows(IOException.class, () -> new FileCatalog(root));
    }

    @Test void expiryOnlyRemovesOldUntrackedUuidArtifacts() throws Exception {
        FileCatalog catalog = new FileCatalog(root);
        UUID old = UUID.randomUUID();
        UUID live = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        catalog.createPart(old);
        catalog.createPart(live);
        catalog.createPart(recent);
        Path unrelated = root.resolve("partial").resolve("manual.part");
        Files.writeString(unrelated, "keep");
        Path oldPart = root.resolve("partial").resolve(old + ".part");
        Path livePart = root.resolve("partial").resolve(live + ".part");
        Files.setLastModifiedTime(oldPart, FileTime.fromMillis(1));
        Files.setLastModifiedTime(livePart, FileTime.fromMillis(1));
        catalog.cleanupOrphans(1000, Set.of(live));
        assertFalse(Files.exists(oldPart));
        assertTrue(Files.exists(livePart));
        assertTrue(Files.exists(root.resolve("partial").resolve(recent + ".part")));
        assertTrue(Files.exists(unrelated));
    }

    @Test void symlinkedPartIsNeverReadOrWritten() throws Exception {
        FileCatalog catalog = new FileCatalog(root.resolve("runtime"));
        UUID id = UUID.randomUUID();
        Path outside = root.resolve("outside.bin");
        Files.write(outside, new byte[]{9});
        Path link = root.resolve("runtime/partial").resolve(id + ".part");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("Symlinks require OS permission: " + unavailable.getClass().getSimpleName());
        }
        assertThrows(IOException.class, () -> catalog.writePart(id, 0, new byte[]{1}));
        assertThrows(IOException.class, () -> catalog.partHash(id));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(outside));
    }

    @Test void recentMetadataKeepsAnOldPartialUntilTheWholeTransferExpires() throws Exception {
        FileCatalog catalog = new FileCatalog(root);
        UUID id = UUID.randomUUID();
        catalog.createPart(id);
        Path part = root.resolve("partial").resolve(id + ".part");
        Files.setLastModifiedTime(part, FileTime.fromMillis(1));
        catalog.saveTransfer(id, new java.util.Properties());
        catalog.cleanupOrphans(1000, Set.of());
        assertTrue(Files.exists(part));
        Path metadata = root.resolve("partial").resolve(id + ".properties");
        Files.setLastModifiedTime(metadata, FileTime.fromMillis(1));
        catalog.cleanupOrphans(1000, Set.of());
        assertFalse(Files.exists(part));
        assertFalse(Files.exists(metadata));
    }
}
