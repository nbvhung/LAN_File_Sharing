package vn.edu.lanfileshare.common.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Reads and writes the v1 length-prefixed TCP frame. Not thread safe. */
public final class FrameCodec {
    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_FRAME_LENGTH = 1_048_576;
    public static final int MAX_CONTROL_PAYLOAD = 65_536;
    public static final int MAX_CHUNK_DATA = 65_536;

    private FrameCodec() { }

    public static Frame read(InputStream input) throws IOException {
        DataInputStream in = new DataInputStream(input);
        int length;
        try {
            length = in.readInt();
        } catch (EOFException eof) {
            throw new EOFException("EOF before frame length or incomplete frame length");
        }
        if (length < 1 || length > MAX_FRAME_LENGTH) {
            throw new IOException("Invalid frame length: " + length);
        }
        int code = in.readUnsignedByte();
        MessageType type;
        try {
            type = MessageType.fromCode(code);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid frame type: " + code, ex);
        }
        int payloadLength = length - 1;
        if (type != MessageType.FILE_CHUNK && payloadLength > MAX_CONTROL_PAYLOAD) {
            throw new IOException("Control payload too large: " + payloadLength);
        }
        byte[] payload = new byte[payloadLength];
        in.readFully(payload);
        return new Frame(type, payload);
    }

    public static void write(OutputStream output, Frame frame) throws IOException {
        byte[] payload = frame.payload();
        int length = payload.length + 1;
        if (length > MAX_FRAME_LENGTH || (frame.type() != MessageType.FILE_CHUNK && payload.length > MAX_CONTROL_PAYLOAD)) {
            throw new IOException("Frame payload too large: " + payload.length);
        }
        DataOutputStream out = new DataOutputStream(output);
        out.writeInt(length);
        out.writeByte(frame.type().code());
        out.write(payload);
        out.flush();
    }
}
