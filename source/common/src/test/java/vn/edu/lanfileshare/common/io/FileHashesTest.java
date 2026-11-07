package vn.edu.lanfileshare.common.io;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FileHashesTest {
    @TempDir
    Path directory;

    @Test
    void knownEmptyAndAbcDigests() throws Exception {
        Path file = Files.createFile(directory.resolve("input"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", FileHashes.sha256(file));
        Files.writeString(file, "abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", FileHashes.sha256(file));
    }

    @Test
    void callerRetainsChannelOwnershipAndCanRewind() throws Exception {
        Path file = Files.writeString(directory.resolve("input"), "abc");
        try (FileChannel channel = FileChannel.open(file)) {
            assertEquals(FileHashes.sha256(file), FileHashes.sha256(channel));
            assertTrue(channel.isOpen());
            assertEquals(3, channel.position());
            channel.position(0);
            assertEquals(FileHashes.sha256(file), FileHashes.sha256(channel));
        }
    }
}
