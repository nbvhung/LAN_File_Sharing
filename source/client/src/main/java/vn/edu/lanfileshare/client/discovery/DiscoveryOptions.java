package vn.edu.lanfileshare.client.discovery;

import vn.edu.lanfileshare.common.protocol.DiscoveryProtocol;

/** Production defaults are v1's 800 ms and three attempts; shorter settings aid deterministic tests. */
public record DiscoveryOptions(int timeoutMillis, int attempts) {
    public DiscoveryOptions {
        if (timeoutMillis < 1 || timeoutMillis > DiscoveryProtocol.TIMEOUT_MILLIS
                || attempts < 1 || attempts > DiscoveryProtocol.MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Discovery allows 1..800 ms per attempt and 1..3 attempts");
        }
    }

    public static DiscoveryOptions defaults() {
        return new DiscoveryOptions(DiscoveryProtocol.TIMEOUT_MILLIS, DiscoveryProtocol.MAX_ATTEMPTS);
    }
}
