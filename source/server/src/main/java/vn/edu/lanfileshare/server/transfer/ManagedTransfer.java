package vn.edu.lanfileshare.server.transfer;

import java.io.IOException;
import java.util.UUID;

/** In-process identity survives socket replacement; disk snapshots do not authorize restart recovery. */
public interface ManagedTransfer extends AutoCloseable {
    TransferSnapshot snapshot();

    void resume(UUID owner, UUID requestId) throws IOException;

    void interrupted() throws IOException;

    @Override
    void close() throws IOException;
}
