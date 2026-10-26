package vn.edu.lanfileshare.server.transfer;

import vn.edu.lanfileshare.common.error.ErrorCode;

/** Safe message for ERROR payloads. Log the cause server-side only. */
public final class TransferException extends Exception {
    private final ErrorCode code;

    public TransferException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public TransferException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() { return code; }
}
