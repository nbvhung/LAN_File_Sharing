package vn.edu.lanfileshare.common.protocol;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.common.model.Messages.*;

class StrictProtocolTest {
    private Frame json(MessageType type, String json) { return new Frame(type, json.getBytes(StandardCharsets.UTF_8)); }
    private String envelope() { return "\"protocolVersion\":1,\"requestId\":\"" + UUID.randomUUID() + "\""; }
    @Test void rejectsInvalidJsonEnvelopeTypesAndUuid() {
        for (String text : new String[]{"", "[]", "{", "{}", "{\"protocolVersion\":1,\"requestId\":\"1-1-1-1-1\"}",
            "{\"protocolVersion\":\"1\",\"requestId\":\"" + UUID.randomUUID() + "\"}",
            "{" + envelope() + "} {}", "{" + envelope() + ",\"protocolVersion\":1}"}) {
            assertThrows(IOException.class, () -> ControlCodec.decode(json(MessageType.LIST_FILES, text), ListFiles.class), text);
        }
    }
    @Test void rejectsMissingNullCoercedFieldsAndInvalidMetadata() {
        String prefix = "{" + envelope();
        for (String fields : new String[]{"", ",\"fileId\":null", ",\"fileId\":\"1-1-1-1-1\""})
            assertThrows(IOException.class, () -> ControlCodec.decode(json(MessageType.DOWNLOAD_INIT, prefix + fields + "}"), DownloadInit.class));
        for (String size : new String[]{"-1", "1.5", "\"2\"", "null", "9223372036854775808"}) {
            String text = prefix + ",\"name\":\"a\",\"size\":" + size + ",\"sha256\":\"" + "a".repeat(64) + "\"}";
            assertThrows(IOException.class, () -> ControlCodec.decode(json(MessageType.UPLOAD_INIT, text), UploadInit.class));
        }
        assertThrows(IOException.class, () -> ControlCodec.decode(json(MessageType.FILE_LIST, prefix + ",\"files\":[{}]}"), FileList.class));
    }
    @Test void optionalErrorTransferIdAndEmptyListAreValid() throws Exception {
        var error = ControlCodec.decode(json(MessageType.ERROR, "{" + envelope() + ",\"code\":\"BAD_REQUEST\",\"message\":\"Invalid request\"}"), ErrorMessage.class);
        assertNull(error.transferId());
        assertTrue(ControlCodec.decode(json(MessageType.FILE_LIST, "{" + envelope() + ",\"files\":[]}"), FileList.class).files().isEmpty());
    }
    @Test void emptyPayloadAndFrameBoundaries() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameCodec.write(out, new Frame(MessageType.LIST_FILES, new byte[0]));
        assertEquals(0, FrameCodec.read(new ByteArrayInputStream(out.toByteArray())).payload().length);
        for (int length : new int[]{0, -1, 1048577}) {
            byte[] bytes = ByteBuffer.allocate(4).putInt(length).array();
            assertThrows(IOException.class, () -> FrameCodec.read(new ByteArrayInputStream(bytes)));
        }
        for (byte[] bytes : new byte[][]{new byte[]{0}, new byte[]{0,0,0,1}, new byte[]{0,0,0}})
            assertThrows(EOFException.class, () -> FrameCodec.read(new ByteArrayInputStream(bytes)));
        assertThrows(IOException.class, () -> FrameCodec.write(out, new Frame(MessageType.LIST_FILES, new byte[65537])));
    }
    @Test void chunkBoundariesAndNegativeWireValues() throws Exception {
        byte[] max = FileChunkCodec.encode(new FileChunk(UUID.randomUUID(), 0, new byte[65536]));
        assertEquals(65536, FileChunkCodec.decode(max).data().length);
        for (int length : new int[]{0, -1, 65537}) {
            byte[] wire = ByteBuffer.allocate(29).putLong(0).putLong(0).putLong(0).putInt(length).put((byte)1).array();
            assertThrows(IOException.class, () -> FileChunkCodec.decode(wire));
        }
        ByteBuffer.wrap(max).putLong(16, -1);
        assertThrows(IOException.class, () -> FileChunkCodec.decode(max));
        assertThrows(IOException.class, () -> FileChunkCodec.decode(new byte[28]));
    }
}
