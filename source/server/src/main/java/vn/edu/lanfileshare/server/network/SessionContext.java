package vn.edu.lanfileshare.server.network;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import vn.edu.lanfileshare.common.protocol.Frame;
import vn.edu.lanfileshare.common.protocol.FrameCodec;

/** Only ClientSession reads the socket. Services may send through this serialized writer. */
public final class SessionContext {
    private static final System.Logger LOG = System.getLogger(SessionContext.class.getName());
    private long sentBytes, receivedBytes;

    public String counters() {
        return "sentDataBytes=" + sentBytes + " receivedDataBytes=" + receivedBytes;
    }

    private final UUID id = UUID.randomUUID();
    private final OutputStream output;
    private final vn.edu.lanfileshare.common.io.PayloadMetrics metrics;

    public SessionContext(OutputStream output) {
        this(output, new vn.edu.lanfileshare.common.io.PayloadMetrics());
    }

    public SessionContext(OutputStream output, vn.edu.lanfileshare.common.io.PayloadMetrics metrics) {
        this.output = output;
        this.metrics = metrics;
    }

    public void received(Frame frame) {
        metrics.received(frame);
        receivedBytes += dataBytes(frame);
        event("receive", frame);
    }

    private static int dataBytes(Frame frame) {
        return frame.type() == vn.edu.lanfileshare.common.protocol.MessageType.FILE_CHUNK ? frame.payload().length - 28
                : 0;
    }

    private void event(String action, Frame frame) {
        if (frame.type() == vn.edu.lanfileshare.common.protocol.MessageType.FILE_CHUNK)
            return;
        if (frame.type() == vn.edu.lanfileshare.common.protocol.MessageType.PROGRESS_ACK)
            return;
        String transfer = "", code = "";
        try {
            if (frame.type() == vn.edu.lanfileshare.common.protocol.MessageType.TRANSFER_COMPLETE)
                transfer = " transferId=" + vn.edu.lanfileshare.common.protocol.ControlCodec
                        .decode(frame, vn.edu.lanfileshare.common.model.Messages.TransferComplete.class).transferId();
            if (frame.type() == vn.edu.lanfileshare.common.protocol.MessageType.TRANSFER_READY)
                transfer = " transferId=" + vn.edu.lanfileshare.common.protocol.ControlCodec
                        .decode(frame, vn.edu.lanfileshare.common.model.Messages.TransferReady.class).transferId();
            if (frame.type() == vn.edu.lanfileshare.common.protocol.MessageType.ERROR)
                code = " code=" + vn.edu.lanfileshare.common.protocol.ControlCodec
                        .decode(frame, vn.edu.lanfileshare.common.model.Messages.ErrorMessage.class).code();
        } catch (IOException ex) {
            code = " code=INVALID_FRAME";
        }
        LOG.log(System.Logger.Level.INFO,
                "sessionId=" + id + " action=" + action + " type=" + frame.type() + transfer + code + " " + counters());
    }

    public UUID id() {
        return id;
    }

    public synchronized void send(Frame frame) throws IOException {
        FrameCodec.write(output, frame);
        metrics.sent(frame);
        sentBytes += dataBytes(frame);
        event("send", frame);
    }
}
