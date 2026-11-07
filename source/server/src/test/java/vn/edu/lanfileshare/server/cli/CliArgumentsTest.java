package vn.edu.lanfileshare.server.cli;

import java.nio.charset.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CliArgumentsTest {
    @Test
    void normalPositionalArgumentsRemainSupported() throws Exception {
        assertArrayEquals(new String[] { "49000", "127.0.0.1", "runtime/server" },
                CliArguments.decode(new String[] { "49000", "127.0.0.1", "runtime/server" }));
    }

    @Test
    void unicodeAndSpacesSurviveAsciiTransport() throws Exception {
        String path = "D:\\lập trình mạng\\runtime\\tệp mẫu.txt";
        String encoded = Base64.getEncoder().encodeToString(path.getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(new String[] { path }, CliArguments.decode(new String[] { "--base64-args", encoded }));
    }

    @Test
    void malformedBase64AndUtf8AreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CliArguments.decode(new String[] { "--base64-args", "???" }));
        assertThrows(CharacterCodingException.class,
                () -> CliArguments.decode(new String[] { "--base64-args", "/w==" }));
    }
}
