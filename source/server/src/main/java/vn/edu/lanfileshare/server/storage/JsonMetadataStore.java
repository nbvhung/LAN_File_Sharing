package vn.edu.lanfileshare.server.storage;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.*;

/** One strict JSON record per UUID; temporary metadata is never a catalog entry. */
final class JsonMetadataStore {
    private static final System.Logger LOG = System.getLogger(JsonMetadataStore.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final StorageConfiguration configuration;
    JsonMetadataStore(StorageConfiguration configuration) { this.configuration = configuration; }
    List<FileInfo> readAll() throws IOException {
        configuration.initialize();
        List<FileInfo> files = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(configuration.metadata(), "*.json")) {
            for (Path entry : entries) {
                try {
                    String filename = entry.getFileName().toString();
                    UUID id = UUID.fromString(filename.substring(0, filename.length() - 5));
                    if (!filename.equals(id + ".json") || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(entry) > 4096) throw new IOException("Invalid metadata entry");
                    JsonNode metadata;
                    try (var input = Files.newInputStream(entry, LinkOption.NOFOLLOW_LINKS)) { metadata = JSON.readTree(input); }
                    var envelope = JSON.createObjectNode();
                    envelope.put("protocolVersion", 1).put("requestId", new UUID(0, 0).toString());
                    envelope.putArray("files").add(metadata);
                    FileInfo file = ControlCodec.decode(new Frame(MessageType.FILE_LIST, JSON.writeValueAsBytes(envelope)), FileList.class).files().getFirst();
                    if (!file.fileId().equals(id)) throw new IOException("Metadata filename/ID mismatch");
                    files.add(file);
                } catch (IOException | IllegalArgumentException ex) {
                    LOG.log(System.Logger.Level.WARNING, "Ignoring invalid metadata entry: " + entry.getFileName(), ex);
                }
            }
        }
        return files;
    }
    void write(FileInfo file) throws IOException {
        configuration.initialize();
        Path target = configuration.metadata().resolve(file.fileId() + ".json");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("File ID already registered");
        Path temporary = Files.createTempFile(configuration.metadata(), "record-", ".tmp");
        try {
            Files.write(temporary, JSON.writeValueAsBytes(file));
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }
}
