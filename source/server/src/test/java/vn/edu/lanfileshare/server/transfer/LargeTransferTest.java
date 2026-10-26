package vn.edu.lanfileshare.server.transfer;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.edu.lanfileshare.server.storage.FileCatalog;
import vn.edu.lanfileshare.server.storage.Sha256;
import vn.edu.lanfileshare.server.transfer.TransferService.*;

/** Streams data on disk; never allocates a whole 100 MiB file in memory. Not a LAN benchmark. */
class LargeTransferTest {
    @TempDir Path root;

    @ParameterizedTest @ValueSource(ints = {1, 10, 100})
    void streamedRoundTripWithResumeInBothDirections(int mebibytes) throws Exception {
        Path source = root.resolve("source.bin");
        byte[] buffer = new byte[65_536];
        Random random = new Random(123);
        try (var out = Files.newOutputStream(source)) {
            for (int i = 0; i < mebibytes * 16; i++) {
                random.nextBytes(buffer);
                out.write(buffer);
            }
        }
        long size = Files.size(source);
        String expectedHash = Sha256.of(source);
        TransferService service = new TransferService(new FileCatalog(root.resolve("runtime")));
        UUID session = UUID.randomUUID();
        Ready upload = service.initUpload(session, "large.bin", size, expectedHash).ready();
        long uploaded = 0;
        try (var input = Files.newInputStream(source)) {
            int length;
            while ((length = input.read(buffer)) != -1) {
                UploadProgress progress = service.acceptUploadChunk(session,
                        new FileChunk(upload.transferId(), uploaded, Arrays.copyOf(buffer, length)));
                uploaded += length;
                assertEquals(uploaded, progress.ack().nextOffset());
                if (uploaded == size / 2) {
                    service.interrupt(session);
                    session = UUID.randomUUID();
                    assertEquals(uploaded, service.resume(session, resume(upload)).ready().nextOffset());
                }
                assertEquals(uploaded == size, progress.completion().isPresent());
            }
        }
        assertEquals(1, service.listFiles().size());
        Ready download = service.initDownload(session, upload.fileId());
        MessageDigest receivedHash = MessageDigest.getInstance("SHA-256");
        long downloaded = 0;
        while (downloaded < size) {
            FileChunk chunk = service.acknowledgeDownload(session, download.transferId(), downloaded).orElseThrow();
            assertEquals(downloaded, chunk.offset());
            byte[] bytes = chunk.data();
            receivedHash.update(bytes);
            downloaded += bytes.length;
            if (downloaded == size / 2) {
                // Last received chunk is on the client, but its ACK has not reached the server.
                service.interrupt(session);
                session = UUID.randomUUID();
                service.resume(session, resume(download));
            }
        }
        assertEquals(size, downloaded);
        assertEquals(expectedHash, HexFormat.of().formatHex(receivedHash.digest()));
        service.completeDownload(session,
                new Completion(download.transferId(), download.fileId(), size, expectedHash));
    }

    private static ResumeRequest resume(Ready ready) {
        return new ResumeRequest(ready.transferId(), ready.direction(), ready.fileId(), ready.size(), ready.sha256());
    }
}
