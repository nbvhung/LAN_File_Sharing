package vn.edu.lanfileshare.common.io;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PrefixHashTest {
    @TempDir
    Path directory;

    @Test
    void prefixSnapshotIsIndependentOfUncommittedTailAndDetectsTampering() throws Exception {
        Path file = Files.writeString(directory.resolve("part"), "abcdef");
        String prefix = PrefixHash.read(file, 3).value(file, 3);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", prefix);
        Files.writeString(file, "XYZ", StandardOpenOption.APPEND);
        assertEquals(prefix, PrefixHash.read(file, 3).value(file, 3));
        Files.write(file, new byte[] { 9 }, StandardOpenOption.WRITE);
        assertNotEquals(prefix, PrefixHash.read(file, 3).value(file, 3));
        assertThrows(java.io.IOException.class, () -> PrefixHash.read(file, 100));
    }

    @Test
    void snapshotsDoNotConsumeTheStreamingDigest() throws Exception {
        Path file = Files.writeString(directory.resolve("part"), "abcdef");
        PrefixHash digest = PrefixHash.empty();
        digest.update(new byte[] { 97, 98, 99 });
        assertEquals(PrefixHash.read(file, 3).value(file, 3), digest.value(file, 3));
        digest.update(new byte[] { 100, 101, 102 });
        assertEquals(FileHashes.sha256(file), digest.value(file, 6));
    }
}
