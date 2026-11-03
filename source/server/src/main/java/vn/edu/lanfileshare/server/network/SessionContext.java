package vn.edu.lanfileshare.server.network;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import vn.edu.lanfileshare.common.protocol.Frame;
import vn.edu.lanfileshare.common.protocol.FrameCodec;
/** Only ClientSession reads the socket. Services may send through this serialized writer. */
public final class SessionContext {
    private final UUID id = UUID.randomUUID();
    private final OutputStream output;
    public SessionContext(OutputStream output) { this.output = output; }
    public UUID id() { return id; }
    public synchronized void send(Frame frame) throws IOException { FrameCodec.write(output, frame); }
}
