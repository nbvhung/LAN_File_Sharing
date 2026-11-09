package vn.edu.lanfileshare.common.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/** UDP discovery defaults and wire prefixes from docs/protocol.md. */
public final class DiscoveryProtocol {
    public static final int DEFAULT_TCP_PORT = 49000;
    public static final int DEFAULT_UDP_PORT = 49001;
    public static final int MAX_PACKET_BYTES = 128;
    public static final int TIMEOUT_MILLIS = 800;
    public static final int MAX_ATTEMPTS = 3;
    public static final String REQUEST_PREFIX = "LANFS_DISCOVER/1 ";
    public static final String RESPONSE_PREFIX = "LANFS_HERE/1 ";

    private static final Pattern NONCE = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern PORT = Pattern.compile("[0-9]{1,5}");

    public static byte[] request(String nonce) {
        requireNonce(nonce);
        return (REQUEST_PREFIX + nonce).getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] response(String nonce, int tcpPort) {
        requireNonce(nonce);
        if (tcpPort < 1 || tcpPort > 65535) {
            throw new IllegalArgumentException("TCP port must be between 1 and 65535");
        }
        return (RESPONSE_PREFIX + nonce + " " + tcpPort).getBytes(StandardCharsets.US_ASCII);
    }

    public static Optional<String> parseRequest(byte[] data, int offset, int length) {
        String message = ascii(data, offset, length);
        if (message == null || !message.startsWith(REQUEST_PREFIX)) {
            return Optional.empty();
        }
        String nonce = message.substring(REQUEST_PREFIX.length());
        return validNonce(nonce) ? Optional.of(nonce) : Optional.empty();
    }

    public static OptionalInt parseResponse(byte[] data, int offset, int length, String expectedNonce) {
        requireNonce(expectedNonce);
        String message = ascii(data, offset, length);
        String prefix = RESPONSE_PREFIX + expectedNonce + " ";
        if (message == null || !message.startsWith(prefix)) {
            return OptionalInt.empty();
        }
        String port = message.substring(prefix.length());
        if (!PORT.matcher(port).matches()) {
            return OptionalInt.empty();
        }
        int value = Integer.parseInt(port);
        return value >= 1 && value <= 65535 ? OptionalInt.of(value) : OptionalInt.empty();
    }

    private static String ascii(byte[] data, int offset, int length) {
        if (data == null || length < 1 || length > MAX_PACKET_BYTES || offset < 0
                || offset > data.length - length) {
            return null;
        }
        for (int i = offset; i < offset + length; i++) {
            if (data[i] < 0) {
                return null;
            }
        }
        return new String(data, offset, length, StandardCharsets.US_ASCII);
    }

    private static boolean validNonce(String nonce) {
        return nonce != null && NONCE.matcher(nonce).matches();
    }

    private static void requireNonce(String nonce) {
        if (!validNonce(nonce)) {
            throw new IllegalArgumentException("Nonce must contain exactly 32 lowercase hexadecimal characters");
        }
    }

    private DiscoveryProtocol() {
    }
}
