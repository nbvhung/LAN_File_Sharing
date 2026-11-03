package vn.edu.lanfileshare.client.transfer;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.FileChunk;
class DownloadFileTest {
    @TempDir Path directory;
    private TransferReady ready() {
        return new TransferReady(1, UUID.randomUUID(), UUID.randomUUID(), Direction.DOWNLOAD, UUID.randomUUID(), "remote.txt", 3,
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", 0);
    }
    @Test void verifiedPublicationIsExclusiveAndRemovesOnlyItsOwnPart() throws Exception {
        TransferReady ready = ready(); Path target = directory.resolve("chosen.txt");
        try (DownloadFile file = new DownloadFile(target, ready, ready.requestId())) {
            assertEquals(3, file.write(new FileChunk(ready.transferId(), 0, new byte[]{97,98,99})));
            assertFalse(Files.exists(target)); file.verifyAndPublish(); file.completed();
            assertEquals("abc", Files.readString(target)); assertFalse(Files.exists(file.partPath()));
        }
        try (var entries = Files.list(directory)) { assertEquals(1, entries.count()); }
    }
    @Test void concurrentTargetCreationNeverReplacesOldBytes() throws Exception {
        TransferReady ready = ready(); Path target = directory.resolve("chosen.txt");
        try (DownloadFile file = new DownloadFile(target, ready, ready.requestId())) {
            file.write(new FileChunk(ready.transferId(), 0, new byte[]{97,98,99}));
            Files.writeString(target, "old bytes", StandardOpenOption.CREATE_NEW);
            assertThrows(FileAlreadyExistsException.class, file::verifyAndPublish);
            file.abort(TransferStatus.FAILED); assertEquals("old bytes", Files.readString(target));
        }
    }
    @Test void duplicateDataIsNotWrittenAgain() throws Exception {
        TransferReady ready = ready();
        try (DownloadFile file = new DownloadFile(directory.resolve("chosen"), ready, ready.requestId())) {
            file.write(new FileChunk(ready.transferId(), 0, new byte[]{97,98}));
            assertEquals(2, file.write(new FileChunk(ready.transferId(), 0, new byte[]{9,9})));
            assertEquals(2, Files.size(file.partPath())); file.abort(TransferStatus.FAILED);
        }
    }
}
