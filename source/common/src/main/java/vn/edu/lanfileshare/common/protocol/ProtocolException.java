package vn.edu.lanfileshare.common.protocol;

import java.io.IOException;
import vn.edu.lanfileshare.common.error.ErrorCode;

/** A recoverable control validation error or a fatal framing/version violation. */
public class ProtocolException extends IOException {
    private final ErrorCode code;
    private final boolean fatal;

    public ProtocolException(ErrorCode code, String message, boolean fatal) {
        super(message);
        this.code = code;
        this.fatal = fatal;
    }

    public ErrorCode code() {
        return code;
    }

    public boolean fatal() {
        return fatal;
    }
}
