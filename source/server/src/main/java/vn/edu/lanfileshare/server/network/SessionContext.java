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
    private final vn.edu.lanfileshare.common.io.PayloadMetrics metrics;
    public SessionContext(OutputStream output) { this(output, new vn.edu.lanfileshare.common.io.PayloadMetrics()); }
    public SessionContext(OutputStream output, vn.edu.lanfileshare.common.io.PayloadMetrics metrics) { this.output = output; this.metrics = metrics; }
    public void received(Frame frame) { metrics.received(frame); }
    public UUID id() { return id; }
    public synchronized void send(Frame frame) throws IOException { FrameCodec.write(output, frame); metrics.sent(frame); }
}
