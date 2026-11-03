package vn.edu.lanfileshare.common.cli;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Base64;

/** Optional ASCII transport used only by local PowerShell launchers, never on TCP. */
public final class CliArguments {
    private CliArguments() {}
    public static String[] decode(String[] arguments) throws CharacterCodingException {
        if (arguments.length == 0 || !arguments[0].equals("--base64-args")) return arguments.clone();
        String[] decoded = new String[arguments.length - 1];
        for (int i = 0; i < decoded.length; i++) {
            byte[] bytes = Base64.getDecoder().decode(arguments[i + 1]);
            decoded[i] = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        }
        return decoded;
    }
}
