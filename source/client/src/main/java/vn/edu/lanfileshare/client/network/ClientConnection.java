package vn.edu.lanfileshare.client.network;

import java.io.IOException;
import java.net.Socket;
import java.util.UUID;
import vn.edu.lanfileshare.common.protocol.*;
import vn.edu.lanfileshare.common.model.Messages.ErrorMessage;

/** Framed I/O usable only inside TcpClient.exchange; the client retains socket ownership. */
public final class ClientConnection {
    private final Socket socket;
    private final vn.edu.lanfileshare.common.io.PayloadMetrics metrics;
    ClientConnection(Socket socket) { this(socket, new vn.edu.lanfileshare.common.io.PayloadMetrics()); }
    ClientConnection(Socket socket, vn.edu.lanfileshare.common.io.PayloadMetrics metrics) { this.socket = socket; this.metrics = metrics; }
    public void send(Frame frame) throws IOException { FrameCodec.write(socket.getOutputStream(), frame); metrics.sent(frame); }
    public Frame receive(UUID requestId) throws IOException {
        Frame frame = FrameCodec.read(socket.getInputStream());
        if (frame.type() == MessageType.FILE_CHUNK) {
            try { FileChunkCodec.decode(frame.payload()); }
            catch (IOException ex) { throw new ProtocolException(vn.edu.lanfileshare.common.error.ErrorCode.INVALID_FRAME, "Invalid FILE_CHUNK payload", true); }
            metrics.received(frame);
            return frame; // Binary chunks have no JSON requestId; the receiver checks transferId and offset.
        }
        ControlCodec.validate(frame);
        if (!requestId.equals(ControlCodec.requestId(frame))) throw new IOException("Response requestId mismatch");
        if (frame.type() == MessageType.ERROR)
            throw new ServerResponseException(ControlCodec.decode(frame, ErrorMessage.class));
        return frame;
    }
}
