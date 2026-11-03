package vn.edu.lanfileshare.common.io;
import java.util.concurrent.atomic.AtomicLong;
import vn.edu.lanfileshare.common.protocol.*;
/** Counts data bytes in fully written/read FILE_CHUNK frames, excluding framing/UUID/offset/JSON. */
public final class PayloadMetrics {
    private final AtomicLong sent = new AtomicLong(), received = new AtomicLong();
    public record Snapshot(long sentDataBytes, long receivedDataBytes) {}
    private static long bytes(Frame frame) { return frame.type() == MessageType.FILE_CHUNK ? Math.max(0, frame.payload().length - 28) : 0; }
    public void sent(Frame frame) { sent.addAndGet(bytes(frame)); }
    public void received(Frame frame) { received.addAndGet(bytes(frame)); }
    public Snapshot snapshot() { return new Snapshot(sent.get(), received.get()); }
}
