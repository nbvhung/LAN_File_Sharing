package vn.edu.lanfileshare.client;

import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.client.network.*;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.*;
import vn.edu.lanfileshare.server.network.*;
import vn.edu.lanfileshare.server.storage.*;

class FileListingIntegrationTest {
    @TempDir
    Path temporary;

    @BeforeAll
    static void warmEntropy() {
        UUID.randomUUID();
    }

    private TcpServer start(FileCatalog catalog) throws Exception {
        return start(catalog, 0);
    }

    private TcpServer start(FileCatalog catalog, int port) throws Exception {
        TcpServer server = new TcpServer(InetAddress.getLoopbackAddress(), port, 8, 3000,
                new RequestDispatcher(catalog, (session, frame) -> fail("No transfer in Phase 2")));
        server.start();
        return server;
    }

    private FileInfo add(FileStorageService storage, JsonFileCatalog catalog, Path input, String name)
            throws Exception {
        FileInfo file = storage.importVerified(input, name);
        catalog.registerCompletedFile(file);
        return file;
    }

    @Test
    void realClientListsActualFilesBeforeAndAfterServerRestart() throws Exception {
        StorageConfiguration config = new StorageConfiguration(temporary.resolve("runtime"));
        FileStorageService storage = new FileStorageService(config);
        JsonFileCatalog catalog = new JsonFileCatalog(storage);
        Path input = Files.writeString(temporary.resolve("local.txt"), "abc");
        List<FileInfo> expected = List.of(add(storage, catalog, input, "same.txt"),
                add(storage, catalog, input, "same.txt"));
        int port;
        try (TcpServer server = start(catalog); TcpClient client = new TcpClient(3000)) {
            port = server.port();
            client.connect("127.0.0.1", server.port());
            assertEquals(new HashSet<>(expected), new HashSet<>(client.listFiles().files()));
        }
        try (TcpServer restarted = start(new JsonFileCatalog(new FileStorageService(config)), port);
                TcpClient client = new TcpClient(3000)) {
            client.connect("127.0.0.1", restarted.port());
            assertEquals(new HashSet<>(expected), new HashSet<>(client.listFiles().files()));
            try (Socket wire = new Socket("127.0.0.1", restarted.port())) {
                wire.setSoTimeout(3000);
                UUID requestId = UUID.randomUUID();
                FrameCodec.write(wire.getOutputStream(),
                        ControlCodec.encode(MessageType.LIST_FILES, new ListFiles(1, requestId)));
                Frame response = FrameCodec.read(wire.getInputStream());
                assertEquals(MessageType.FILE_LIST, response.type());
                var decoded = ControlCodec.decode(response, FileList.class);
                assertEquals(1, decoded.protocolVersion());
                assertEquals(requestId, decoded.requestId());
                assertEquals(new HashSet<>(expected), new HashSet<>(decoded.files()));
                String json = new String(response.payload(), java.nio.charset.StandardCharsets.UTF_8);
                assertFalse(json.contains(temporary.toString()));
                assertFalse(json.contains("path"));
                assertFalse(json.contains("completed"));
            }
        }
    }

    @Test
    void oversizedRealCatalogReturnsCorrelatedErrorWithoutTruncation() throws Exception {
        FileStorageService storage = new FileStorageService(new StorageConfiguration(temporary.resolve("runtime")));
        JsonFileCatalog catalog = new JsonFileCatalog(storage);
        Path input = Files.writeString(temporary.resolve("input"), "x");
        for (int i = 0; i < 200; i++)
            add(storage, catalog, input, "n".repeat(240));
        assertEquals(200, catalog.listFiles().size());
        try (TcpServer server = start(catalog);
                Socket wire = new Socket("127.0.0.1", server.port());
                TcpClient client = new TcpClient(5000)) {
            wire.setSoTimeout(5000);
            for (int i = 0; i < 2; i++) {
                UUID id = UUID.randomUUID();
                FrameCodec.write(wire.getOutputStream(),
                        ControlCodec.encode(MessageType.LIST_FILES, new ListFiles(1, id)));
                Frame response = FrameCodec.read(wire.getInputStream());
                assertEquals(MessageType.ERROR, response.type());
                assertTrue(response.payload().length <= 65536);
                ErrorMessage error = ControlCodec.decode(response, ErrorMessage.class);
                assertEquals(id, error.requestId());
                assertEquals(ErrorCode.SERVER_BUSY, error.code());
                assertFalse(error.message().contains(temporary.toString()));
            }
            client.connect("127.0.0.1", server.port());
            ServerResponseException error = assertThrows(ServerResponseException.class, client::listFiles);
            assertEquals(ErrorCode.SERVER_BUSY, error.code());
            assertNotNull(error.requestId());
        }
        assertEquals(200, catalog.listFiles().size());
    }
}
