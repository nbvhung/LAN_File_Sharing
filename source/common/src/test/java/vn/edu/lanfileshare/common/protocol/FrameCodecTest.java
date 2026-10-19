package vn.edu.lanfileshare.common.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FrameCodecTest {
    @Test void roundTripUsesTypeInclusiveLength() throws IOException {
        byte[] payload = "{\"protocolVersion\":1}".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        FrameCodec.write(bytes, new Frame(MessageType.LIST_FILES, payload));
        assertEquals(payload.length + 1, new java.io.DataInputStream(new ByteArrayInputStream(bytes.toByteArray())).readInt());
        Frame decoded = FrameCodec.read(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(MessageType.LIST_FILES, decoded.type());
        assertArrayEquals(payload, decoded.payload());
    }

    @Test void rejectsInvalidLengthAndTruncatedFrame() throws IOException {
        ByteArrayOutputStream invalid = new ByteArrayOutputStream();
        new DataOutputStream(invalid).writeInt(FrameCodec.MAX_FRAME_LENGTH + 1);
        assertThrows(IOException.class, () -> FrameCodec.read(new ByteArrayInputStream(invalid.toByteArray())));

        ByteArrayOutputStream truncated = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(truncated);
        out.writeInt(4);
        out.writeByte(MessageType.FILE_LIST.code());
        out.writeByte(1);
        assertThrows(EOFException.class, () -> FrameCodec.read(new ByteArrayInputStream(truncated.toByteArray())));
    }

    @Test void rejectsUnknownType() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(1);
        out.writeByte(0x7f);
        assertThrows(IOException.class, () -> FrameCodec.read(new ByteArrayInputStream(bytes.toByteArray())));
    }
}
