package vn.edu.lanfileshare.client.network;
import java.io.*;
import java.time.Duration;
import java.util.Objects;
/** Finite exponential backoff. Delay is injectable for deterministic policy tests. */
public record ReconnectPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff, Delay delay) {
    @FunctionalInterface public interface Delay { void pause(Duration duration) throws InterruptedException; }
    public ReconnectPolicy {
        if (maxAttempts < 1 || maxAttempts > 20 || initialBackoff.isNegative() || maxBackoff.compareTo(initialBackoff) < 0
            || maxBackoff.compareTo(Duration.ofSeconds(10)) > 0) throw new IllegalArgumentException("Invalid reconnect limits");
        Objects.requireNonNull(delay);
    }
    public static ReconnectPolicy defaults() { return new ReconnectPolicy(3, Duration.ofMillis(100), Duration.ofSeconds(1), d -> Thread.sleep(d.toMillis())); }
    public void backoff(int completedAttempts) throws InterruptedIOException {
        long multiplier = 1L << Math.min(20, Math.max(0, completedAttempts - 1));
        long millis = Math.min(maxBackoff.toMillis(), initialBackoff.toMillis() * multiplier);
        try { delay.pause(Duration.ofMillis(millis)); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); InterruptedIOException io = new InterruptedIOException("Reconnect interrupted"); io.initCause(ex); throw io; }
    }
}
