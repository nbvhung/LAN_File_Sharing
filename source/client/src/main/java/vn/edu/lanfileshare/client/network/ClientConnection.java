package vn.edu.lanfileshare.client.network;

import java.io.IOException;
import java.net.Socket;
import java.util.UUID;
import vn.edu.lanfileshare.common.protocol.*;
import vn.edu.lanfileshare.common.model.Messages.ErrorMessage;

/** Framed I/O usable only inside TcpClient.exchange; the client retains socket ownership. */
public final class ClientConnection {
    private final Socket socket;
    ClientConnection(Socket socket) { this.socket = socket; }
    public void send(Frame frame) throws IOException { FrameCodec.write(socket.getOutputStream(), frame); }
    public Frame receive(UUID requestId) throws IOException {
        Frame frame = FrameCodec.read(socket.getInputStream());
        ControlCodec.validate(frame);
        if (!requestId.equals(ControlCodec.requestId(frame))) throw new IOException("Response requestId mismatch");
        if (frame.type() == MessageType.ERROR)
            throw new ServerResponseException(ControlCodec.decode(frame, ErrorMessage.class));
        return frame;
    }
}
