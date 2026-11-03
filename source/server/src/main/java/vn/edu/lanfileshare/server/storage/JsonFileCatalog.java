package vn.edu.lanfileshare.server.storage;

import java.io.IOException;
import java.util.*;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;

/** Disk-backed catalog: no scan of completed/ or staging/ can implicitly publish a file. */
public final class JsonFileCatalog implements FileCatalog {
    private static final System.Logger LOG = System.getLogger(JsonFileCatalog.class.getName());
    private final FileStorageService storage;
    private final JsonMetadataStore metadata;
    public JsonFileCatalog(FileStorageService storage) throws IOException {
        this.storage = Objects.requireNonNull(storage);
        metadata = new JsonMetadataStore(storage.configuration());
        listFiles(); // Validate persisted entries on startup, without publishing broken records.
    }
    @Override public synchronized List<FileInfo> listFiles() throws IOException {
        List<FileInfo> result = new ArrayList<>();
        for (FileInfo file : metadata.readAll()) {
            try {
                if (storage.matches(file)) result.add(file);
                else LOG.log(System.Logger.Level.WARNING, "Ignoring missing/changed completed file: " + file.fileId());
            } catch (IOException ex) {
                LOG.log(System.Logger.Level.WARNING, "Cannot verify completed file: " + file.fileId(), ex);
            }
        }
        result.sort(Comparator.comparing(file -> file.fileId().toString()));
        return List.copyOf(result);
    }
    /** Caller supplies metadata for an existing UUID slot, never a path from a network payload. */
    public synchronized void registerCompletedFile(FileInfo file) throws IOException {
        if (!storage.matches(file)) throw new IOException("Completed file does not match metadata");
        metadata.write(file);
    }
}
