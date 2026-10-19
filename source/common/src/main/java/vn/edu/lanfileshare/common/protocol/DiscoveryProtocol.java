package vn.edu.lanfileshare.common.protocol;

/** UDP discovery defaults and wire prefixes from docs/protocol.md. */
public final class DiscoveryProtocol {
    public static final int DEFAULT_TCP_PORT = 49000;
    public static final int DEFAULT_UDP_PORT = 49001;
    public static final int MAX_PACKET_BYTES = 128;
    public static final int TIMEOUT_MILLIS = 800;
    public static final int MAX_ATTEMPTS = 3;
    public static final String REQUEST_PREFIX = "LANFS_DISCOVER/1 ";
    public static final String RESPONSE_PREFIX = "LANFS_HERE/1 ";

    private DiscoveryProtocol() { }
}
