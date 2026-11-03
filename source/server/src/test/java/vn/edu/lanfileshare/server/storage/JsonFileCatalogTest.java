package vn.edu.lanfileshare.server.storage;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;

class JsonFileCatalogTest {
    @TempDir Path temporary;
    private FileStorageService storage;
    private JsonFileCatalog catalog;
    @BeforeEach void setup() throws Exception {
        storage = new FileStorageService(new StorageConfiguration(temporary.resolve("runtime")));
        catalog = new JsonFileCatalog(storage);
    }
    private FileInfo add(String content, String name) throws Exception {
        Path local = Files.writeString(temporary.resolve("input"), content);
        FileInfo file = storage.importVerified(local, name);
        catalog.registerCompletedFile(file);
        return file;
    }
    private Path metadata(FileInfo file) { return storage.configuration().metadata().resolve(file.fileId() + ".json"); }
    @Test void emptyCatalogCreatesDirectories() throws Exception {
        assertTrue(catalog.listFiles().isEmpty());
        assertTrue(Files.isDirectory(storage.configuration().completed()));
        assertTrue(Files.isDirectory(storage.configuration().metadata()));
        assertTrue(Files.isDirectory(storage.configuration().staging()));
        assertTrue(catalog.findById(UUID.randomUUID()).isEmpty());
    }
    @Test void oneFileHasActualSizeAndKnownHashAndImmutableSnapshot() throws Exception {
        FileInfo file = add("abc", "notes.txt");
        assertEquals(3, file.size());
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", file.sha256());
        assertEquals(List.of(file), catalog.listFiles());
        assertEquals(file, catalog.findById(file.fileId()).orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> catalog.listFiles().clear());
        assertEquals("abc", Files.readString(storage.completedPath(file.fileId())));
    }
    @Test void multipleFilesAllowDuplicateNamesAndEmptyFile() throws Exception {
        FileInfo first = add("one", "same.txt");
        FileInfo second = add("two", "same.txt");
        FileInfo empty = add("", "empty.txt");
        assertNotEquals(first.fileId(), second.fileId());
        assertEquals(Set.of(first, second, empty), new HashSet<>(catalog.listFiles()));
        assertEquals(0, empty.size());
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", empty.sha256());
    }
    @Test void corruptMetadataDoesNotHideGoodRecordsOrPreventRestart() throws Exception {
        FileInfo good = add("good", "good.txt");
        FileInfo bad = add("bad", "bad.txt");
        Files.writeString(metadata(bad), "{broken");
        JsonFileCatalog restarted = new JsonFileCatalog(new FileStorageService(storage.configuration()));
        assertEquals(List.of(good), restarted.listFiles());
        assertTrue(Files.exists(metadata(bad))); // Preserve diagnostics rather than silently deleting data.
    }
    @Test void missingChangedSizeAndSameSizeTamperingAreExcluded() throws Exception {
        FileInfo missing = add("missing", "missing.txt");
        FileInfo resized = add("old", "resized.txt");
        FileInfo changed = add("old", "changed.txt");
        Files.delete(storage.completedPath(missing.fileId()));
        Files.writeString(storage.completedPath(resized.fileId()), "longer");
        Files.writeString(storage.completedPath(changed.fileId()), "NEW");
        assertTrue(catalog.listFiles().isEmpty());
        assertTrue(new JsonFileCatalog(storage).listFiles().isEmpty());
    }
    @Test void partsOrphanBytesAndUnregisteredImportsNeverAppear() throws Exception {
        Files.writeString(storage.configuration().staging().resolve(UUID.randomUUID() + ".part"), "partial");
        Files.writeString(storage.configuration().completed().resolve(UUID.randomUUID() + ".part"), "partial");
        Files.writeString(storage.configuration().completed().resolve(UUID.randomUUID().toString()), "orphan");
        Files.writeString(storage.configuration().metadata().resolve("incomplete.json.part"), "{}");
        storage.importVerified(Files.writeString(temporary.resolve("local"), "not registered"), "pending.txt");
        assertTrue(new JsonFileCatalog(storage).listFiles().isEmpty());
    }
    @Test void metadataIdMustMatchFilenameAndNoPathFieldIsAccepted() throws Exception {
        FileInfo file = add("hello", "safe.txt");
        Path original = metadata(file);
        String json = Files.readString(original);
        Files.move(original, original.resolveSibling(UUID.randomUUID() + ".json"));
        assertTrue(catalog.listFiles().isEmpty());
        Files.writeString(original, json.substring(0, json.length()-1) + ",\"path\":\"../../secret\"}");
        assertTrue(catalog.listFiles().isEmpty());
    }
    @Test void registrationRequiresVerifiedExistingBytesAndCannotReplaceId() throws Exception {
        FileInfo file = add("abc", "safe.txt");
        assertThrows(IOException.class, () -> catalog.registerCompletedFile(file));
        assertThrows(IOException.class, () -> catalog.registerCompletedFile(new FileInfo(UUID.randomUUID(), "safe", 3, file.sha256())));
        assertThrows(IOException.class, () -> catalog.registerCompletedFile(new FileInfo(file.fileId(), "safe", 3, "0".repeat(64))));
        assertEquals(List.of(file), catalog.listFiles());
    }
    @Test void rejectsUnsafeNamesAndRuntimeInsideSource() throws Exception {
        Path input = Files.writeString(temporary.resolve("input"), "bytes");
        for (String name : new String[]{"../escape", "a/b", "a\\b", "", "a".repeat(256), "bad\nname"})
            assertThrows(IOException.class, () -> storage.importVerified(input, name));
        assertThrows(IllegalArgumentException.class, () -> new StorageConfiguration(temporary.resolve("source/runtime")));
        assertTrue(catalog.listFiles().isEmpty());
    }
    @Test void strictMetadataValidationRejectsInvalidTypesIdsAndMissingFields() throws Exception {
        FileInfo file = add("abc", "safe.txt");
        Path path = metadata(file);
        String valid = Files.readString(path);
        String[] invalid = {"{}", "[]", valid + " {}", valid.replace(file.fileId().toString(), "1-1-1-1-1"),
            valid.replace("\"size\":3", "\"size\":-1"), valid.replace("\"size\":3", "\"size\":\"3\""),
            valid.replace(file.sha256(), "wrong"), valid.replace("safe.txt", "../secret"),
            valid.substring(0,valid.length()-1) + ",\"size\":3}", "x".repeat(4097)};
        for (String text : invalid) {
            Files.writeString(path, text);
            assertTrue(catalog.listFiles().isEmpty(), text);
        }
    }
}
