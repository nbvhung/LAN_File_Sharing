package vn.edu.lanfileshare.common.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscoveryProtocolTest {
    private static final String NONCE = "0123456789abcdef0123456789abcdef";

    @Test
    void exactWireRoundtrip() {
        byte[] request = DiscoveryProtocol.request(NONCE);
        assertEquals("LANFS_DISCOVER/1 " + NONCE, new String(request, StandardCharsets.US_ASCII));
        assertEquals(NONCE, DiscoveryProtocol.parseRequest(request, 0, request.length).orElseThrow());
        byte[] response = DiscoveryProtocol.response(NONCE, 49000);
        assertEquals("LANFS_HERE/1 " + NONCE + " 49000", new String(response, StandardCharsets.US_ASCII));
        assertEquals(49000, DiscoveryProtocol.parseResponse(response, 0, response.length, NONCE).orElseThrow());
    }

    @Test
    void rejectsWrongVersionMalformedNonceAndWhitespace() {
        for (String value : new String[] { "", "LANFS_DISCOVER/2 " + NONCE, "LANFS_DISCOVER/1 " + NONCE.toUpperCase(),
                "LANFS_DISCOVER/1 " + NONCE.substring(1), "LANFS_DISCOVER/1 " + NONCE + "0",
                " LANFS_DISCOVER/1 " + NONCE, "LANFS_DISCOVER/1  " + NONCE,
                "LANFS_DISCOVER/1 " + NONCE + "\n", "LANFS_DISCOVER/1 " + NONCE + "\0" }) {
            byte[] data = value.getBytes(StandardCharsets.US_ASCII);
            assertTrue(DiscoveryProtocol.parseRequest(data, 0, data.length).isEmpty(), value);
        }
    }

    @Test
    void rejectsWrongNonceVersionPortAndExtraFields() {
        for (String value : new String[] { "LANFS_HERE/2 " + NONCE + " 49000",
                "LANFS_HERE/1 " + "f".repeat(32) + " 49000", "LANFS_HERE/1 " + NONCE,
                "LANFS_HERE/1 " + NONCE + " 0", "LANFS_HERE/1 " + NONCE + " 65536",
                "LANFS_HERE/1 " + NONCE + " -1", "LANFS_HERE/1 " + NONCE + " +80",
                "LANFS_HERE/1 " + NONCE + " 80.0", "LANFS_HERE/1 " + NONCE + " 80\n",
                "LANFS_HERE/1 " + NONCE + " 80 192.0.2.1", "LANFS_HERE/1 " + NONCE + "  80" }) {
            byte[] data = value.getBytes(StandardCharsets.US_ASCII);
            assertTrue(DiscoveryProtocol.parseResponse(data, 0, data.length, NONCE).isEmpty(), value);
        }
    }

    @Test
    void honorsPacketOffsetLengthAndStrictAscii() {
        byte[] request = DiscoveryProtocol.request(NONCE);
        byte[] buffer = new byte[128];
        System.arraycopy(request, 0, buffer, 7, request.length);
        assertEquals(NONCE, DiscoveryProtocol.parseRequest(buffer, 7, request.length).orElseThrow());
        assertTrue(DiscoveryProtocol.parseRequest(buffer, 0, buffer.length).isEmpty());
        buffer[7] = (byte) 0xff;
        assertTrue(DiscoveryProtocol.parseRequest(buffer, 7, request.length).isEmpty());
        assertTrue(DiscoveryProtocol.parseRequest(buffer, -1, 5).isEmpty());
        assertTrue(DiscoveryProtocol.parseRequest(buffer, 120, 20).isEmpty());
        assertTrue(DiscoveryProtocol.parseRequest(Arrays.copyOf(request, 129), 0, 129).isEmpty());
        byte[] oversized = Arrays.copyOf(DiscoveryProtocol.response(NONCE, 80), 129);
        assertTrue(DiscoveryProtocol.parseResponse(oversized, 0, oversized.length, NONCE).isEmpty());
    }

    @Test
    void encodersRejectInvalidValuesAndAcceptPortBounds() {
        assertThrows(IllegalArgumentException.class, () -> DiscoveryProtocol.request(null));
        assertThrows(IllegalArgumentException.class, () -> DiscoveryProtocol.request("a"));
        assertThrows(IllegalArgumentException.class, () -> DiscoveryProtocol.response(NONCE, 0));
        assertThrows(IllegalArgumentException.class, () -> DiscoveryProtocol.response(NONCE, 65536));
        for (int port : new int[] { 1, 65535 }) {
            byte[] data = DiscoveryProtocol.response(NONCE, port);
            assertEquals(port, DiscoveryProtocol.parseResponse(data, 0, data.length, NONCE).orElseThrow());
        }
    }
}
