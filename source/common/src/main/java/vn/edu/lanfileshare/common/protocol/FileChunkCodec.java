package vn.edu.lanfileshare.common.protocol;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.io.IOException;
public final class FileChunkCodec {
    private FileChunkCodec() {}
    public static byte[] encode(FileChunk chunk) {
        byte[] data = chunk.data();
        return ByteBuffer.allocate(28 + data.length).putLong(chunk.transferId().getMostSignificantBits())
            .putLong(chunk.transferId().getLeastSignificantBits()).putLong(chunk.offset()).putInt(data.length).put(data).array();
    }
    public static FileChunk decode(byte[] payload) throws IOException {
        if (payload.length < 29 || payload.length > 28 + FrameCodec.MAX_CHUNK_DATA) throw new IOException("Invalid chunk length");
        ByteBuffer b = ByteBuffer.wrap(payload);
        UUID id = new UUID(b.getLong(), b.getLong());
        long offset = b.getLong();
        int length = b.getInt();
        if (length != b.remaining()) throw new IOException("Chunk dataLength mismatch");
        byte[] data = new byte[length]; b.get(data);
        try { return new FileChunk(id, offset, data); }
        catch (IllegalArgumentException ex) { throw new IOException("Invalid chunk", ex); }
    }
}
