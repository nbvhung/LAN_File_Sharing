package vn.edu.lanfileshare.server.storage;

import java.nio.file.Path;

/** Local-only seeding tool for demos before TCP upload exists. */
public final class StorageMain {
    private StorageMain() {}
    public static void main(String[] args) throws Exception {
        args = vn.edu.lanfileshare.server.cli.CliArguments.decode(args);
        if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("Usage: StorageMain <storageDir> <localFile> [displayName]");
        Path input = Path.of(args[1]);
        FileStorageService storage = new FileStorageService(new StorageConfiguration(Path.of(args[0])));
        JsonFileCatalog catalog = new JsonFileCatalog(storage);
        var file = storage.importVerified(input, args.length == 3 ? args[2] : input.getFileName().toString());
        catalog.registerCompletedFile(file);
        System.out.println("Registered fileId=" + file.fileId() + " name=" + file.name() + " size=" + file.size() + " sha256=" + file.sha256());
    }
}
