package vn.edu.lanfileshare.client.transfer;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;

class UploadCheckpointStoreTest {
    @TempDir
    Path directory;

    @Test
    void separateStoreInstancesCanReadDuringAtomicReplacementOnWindows() throws Exception {
        UploadCheckpointStore writer = new UploadCheckpointStore(directory),
                reader = new UploadCheckpointStore(directory);
        UUID id = UUID.randomUUID();
        FileInfo file = new FileInfo(UUID.randomUUID(), "name", 64, "0".repeat(64));
        writer.save(new UploadCheckpoint(1, id, file, directory.resolve("source").toString(), 0,
                TransferStatus.INTERRUPTED, System.currentTimeMillis()));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> reading = executor.submit(() -> {
                for (int i = 0; i < 500; i++)
                    assertTrue(reader.load(id).nextOffset() <= 64);
                return null;
            });
            for (int offset = 1; offset <= 64; offset++)
                writer.save(new UploadCheckpoint(1, id, file, directory.resolve("source").toString(), offset,
                        TransferStatus.INTERRUPTED, System.currentTimeMillis()));
            reading.get(10, TimeUnit.SECONDS);
            assertEquals(64, reader.load(id).nextOffset());
        } finally {
            executor.shutdownNow();
        }
    }
}
