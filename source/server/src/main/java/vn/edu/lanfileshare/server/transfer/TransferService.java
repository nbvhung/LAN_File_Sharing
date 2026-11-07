package vn.edu.lanfileshare.server.transfer;

import java.io.IOException;
import java.util.UUID;
import vn.edu.lanfileshare.common.protocol.Frame;
import vn.edu.lanfileshare.server.network.SessionContext;

/** TV2 implements file operations; dispatcher has already validated the wire payload. */
public interface TransferService {
    void handle(SessionContext session, Frame validatedFrame) throws IOException;

    default void disconnected(UUID sessionId) {
    }

    default java.util.Optional<UUID> requestId(UUID sessionId) {
        return java.util.Optional.empty();
    }
}
