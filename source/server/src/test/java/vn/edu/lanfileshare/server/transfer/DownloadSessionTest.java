package vn.edu.lanfileshare.server.transfer;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.common.io.FileHashes;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.ProtocolException;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.server.storage.*;

class DownloadSessionTest {
    @TempDir
    Path directory;

    @Test
    void ackProgressAndCompletionUseReceiverCommittedOffset() throws Exception {
        StorageConfiguration config = new StorageConfiguration(directory.resolve("runtime"));
        config.initialize();
        Path path = Files.writeString(directory.resolve("source"), "abc");
        FileInfo file = new FileInfo(UUID.randomUUID(), "source", 3, FileHashes.sha256(path));
        TransferStateStore states = new TransferStateStore(config);
        FileChannel source = FileChannel.open(path);
        try (DownloadSession session = new DownloadSession(UUID.randomUUID(), UUID.randomUUID(), file, source,
                states)) {
            var initial = session.snapshot();
            assertEquals(TransferState.INITIALIZED, states.read(initial.transferId()).state());
            assertEquals(ErrorCode.OFFSET_MISMATCH,
                    assertThrows(ProtocolException.class, () -> session.acknowledge(1)).code());
            assertArrayEquals(new byte[] { 97, 98, 99 }, session.acknowledge(0).orElseThrow().data());
            assertEquals(0, states.read(initial.transferId()).nextOffset());
            assertThrows(ProtocolException.class, () -> session.acknowledge(0));
            assertTrue(session.acknowledge(3).isEmpty());
            assertFalse(source.isOpen());
            session.completed(new TransferComplete(1, initial.requestId(), initial.transferId(), file.fileId(),
                    file.size(), file.sha256()));
            assertEquals(TransferState.COMPLETED, states.read(initial.transferId()).state());
            session.interrupted();
            assertEquals(TransferState.COMPLETED, session.snapshot().state());
        }
    }

    @Test
    void emptySourceStillRequiresInitialAckBeforeCompletion() throws Exception {
        StorageConfiguration config = new StorageConfiguration(directory.resolve("runtime"));
        config.initialize();
        Path path = Files.createFile(directory.resolve("empty"));
        FileInfo file = new FileInfo(UUID.randomUUID(), "empty", 0, FileHashes.sha256(path));
        try (DownloadSession session = new DownloadSession(UUID.randomUUID(), UUID.randomUUID(), file,
                FileChannel.open(path), new TransferStateStore(config))) {
            var state = session.snapshot();
            TransferComplete complete = new TransferComplete(1, state.requestId(), state.transferId(), file.fileId(), 0,
                    file.sha256());
            assertThrows(ProtocolException.class, () -> session.completed(complete));
            assertTrue(session.acknowledge(0).isEmpty());
            session.completed(complete);
            assertTrue(session.terminal());
        }
    }

    @Test
    void interruptionClosesHandleAndDoesNotCommitUnacknowledgedBytes() throws Exception {
        StorageConfiguration config = new StorageConfiguration(directory.resolve("runtime"));
        config.initialize();
        Path path = Files.writeString(directory.resolve("source"), "abc");
        FileInfo file = new FileInfo(UUID.randomUUID(), "source", 3, FileHashes.sha256(path));
        TransferStateStore states = new TransferStateStore(config);
        FileChannel source = FileChannel.open(path);
        try (DownloadSession session = new DownloadSession(UUID.randomUUID(), UUID.randomUUID(), file, source,
                states)) {
            session.acknowledge(0);
            session.interrupted();
            var saved = states.read(session.snapshot().transferId());
            assertEquals(Direction.DOWNLOAD, saved.direction());
            assertEquals(0, saved.nextOffset());
            assertEquals(TransferState.INTERRUPTED, saved.state());
            assertFalse(source.isOpen());
        }
    }
}
