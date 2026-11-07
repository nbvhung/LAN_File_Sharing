package vn.edu.lanfileshare.server.cli;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Base64;

/** Optional ASCII transport used only by local PowerShell launchers, never on TCP. */
public final class CliArguments {
    private CliArguments() {
    }

    public static String[] decode(String[] arguments) throws CharacterCodingException {
        return vn.edu.lanfileshare.common.cli.CliArguments.decode(arguments);
    }
}
