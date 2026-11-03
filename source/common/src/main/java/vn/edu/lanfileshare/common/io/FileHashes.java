package vn.edu.lanfileshare.common.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/** Bounded-memory SHA-256. Channel overload consumes from its current position and does not close it. */
public final class FileHashes {
    private FileHashes() {}
    public static String sha256(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return sha256(channel);
        }
    }
    public static String sha256(FileChannel channel) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
        ByteBuffer buffer = ByteBuffer.allocate(65536);
        while (channel.read(buffer) != -1) {
            buffer.flip(); digest.update(buffer); buffer.clear();
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
