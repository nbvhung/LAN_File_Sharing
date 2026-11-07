package vn.edu.lanfileshare.common.protocol;

import java.util.UUID;
import java.util.Objects;

public record FileChunk(UUID transferId, long offset, byte[] data) {
    public FileChunk {
        Objects.requireNonNull(transferId);
        data = Objects.requireNonNull(data).clone();
        if (offset < 0 || data.length < 1 || data.length > FrameCodec.MAX_CHUNK_DATA
                || offset > Long.MAX_VALUE - data.length)
            throw new IllegalArgumentException("Invalid chunk offset or size");
    }

    @Override
    public byte[] data() {
        return data.clone();
    }
}
