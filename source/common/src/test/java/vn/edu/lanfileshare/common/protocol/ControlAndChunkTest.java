package vn.edu.lanfileshare.common.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import vn.edu.lanfileshare.common.model.Messages.*;

class ControlAndChunkTest {
    @Test
    void chunkRoundTripAndRejectsInvalidWireLength() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] bytes = FileChunkCodec.encode(new FileChunk(id, 42, new byte[] { 0, -1, 5 }));
        FileChunk chunk = FileChunkCodec.decode(bytes);
        assertEquals(id, chunk.transferId());
        assertEquals(42, chunk.offset());
        assertArrayEquals(new byte[] { 0, -1, 5 }, chunk.data());
        ByteBuffer.wrap(bytes).putInt(24, 99);
        assertThrows(IOException.class, () -> FileChunkCodec.decode(bytes));
        assertThrows(IllegalArgumentException.class, () -> new FileChunk(id, -1, new byte[] { 1 }));
    }

    @Test
    void controlRejectsMissingFieldsWrongVersionAndUnsafeName() throws Exception {
        UUID id = UUID.randomUUID();
        Frame frame = ControlCodec.encode(MessageType.UPLOAD_INIT,
                new UploadInit(1, id, "../secret", 1, "a".repeat(64)));
        assertThrows(IOException.class, () -> MessageValidator.validate(frame));
        Frame wrong = ControlCodec.encode(MessageType.LIST_FILES, new ListFiles(2, id));
        assertThrows(ControlCodec.UnsupportedVersionException.class, () -> ControlCodec.decode(wrong, ListFiles.class));
        Frame missing = new Frame(MessageType.DOWNLOAD_INIT,
                ("{\"protocolVersion\":1,\"requestId\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> ControlCodec.decode(missing, DownloadInit.class));
    }
}
