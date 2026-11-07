package vn.edu.lanfileshare.client.transfer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import vn.edu.lanfileshare.common.model.Messages.Direction;

public final class TransferInterruptedException extends IOException {
    private final UUID transferId;
    private final Direction direction;
    private final Path checkpoint;
    private final long nextOffset;

    public TransferInterruptedException(UUID id, Direction direction, Path checkpoint, long nextOffset,
            IOException cause) {
        super("Transfer interrupted: transferId=" + id + " direction=" + direction + " offset=" + nextOffset
                + " checkpoint=" + checkpoint, cause);
        this.transferId = id;
        this.direction = direction;
        this.checkpoint = checkpoint;
        this.nextOffset = nextOffset;
    }

    public UUID transferId() {
        return transferId;
    }

    public Direction direction() {
        return direction;
    }

    public Path checkpoint() {
        return checkpoint;
    }

    public long nextOffset() {
        return nextOffset;
    }
}
