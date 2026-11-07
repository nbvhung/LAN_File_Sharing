package vn.edu.lanfileshare.client.network;

import java.io.IOException;
import java.util.UUID;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.model.Messages.ErrorMessage;

/** A valid correlated ERROR response, exposed without parsing exception text. */
public final class ServerResponseException extends IOException {
    private final ErrorCode code;
    private final UUID requestId;

    public ServerResponseException(ErrorMessage error) {
        super("Server error: " + error.code() + ": " + error.message());
        code = error.code();
        requestId = error.requestId();
    }

    public ErrorCode code() {
        return code;
    }

    public UUID requestId() {
        return requestId;
    }
}
