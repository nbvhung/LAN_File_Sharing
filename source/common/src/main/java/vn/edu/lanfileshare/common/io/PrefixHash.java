package vn.edu.lanfileshare.common.io;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/** Streaming prefix integrity, with a bounded-memory fallback for providers without digest cloning. */
public final class PrefixHash {
    private final MessageDigest digest;

    private PrefixHash(MessageDigest digest) {
        this.digest = digest;
    }

    public static PrefixHash empty() {
        try {
            return new PrefixHash(MessageDigest.getInstance("SHA-256"));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static PrefixHash read(Path file, long length) throws IOException {
        if (length < 0)
            throw new IOException("Negative prefix size");
        PrefixHash hash = empty();
        try (FileChannel source = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (source.size() < length)
                throw new EOFException("Partial file is shorter than committed offset");
            ByteBuffer buffer = ByteBuffer.allocate(65536);
            long offset = 0;
            while (offset < length) {
                buffer.clear().limit((int) Math.min(buffer.capacity(), length - offset));
                int count = source.read(buffer);
                if (count == -1)
                    throw new EOFException("Partial file changed while verifying prefix");
                offset += count;
                buffer.flip();
                hash.digest.update(buffer);
            }
        }
        return hash;
    }

    public void update(byte[] data) {
        digest.update(data);
    }

    public String value(Path file, long length) throws IOException {
        try {
            return HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest());
        } catch (CloneNotSupportedException ex) {
            return HexFormat.of().formatHex(read(file, length).digest.digest());
        }
    }
}
