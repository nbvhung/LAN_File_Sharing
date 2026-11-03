package vn.edu.lanfileshare.server;
import java.net.InetAddress;
import java.nio.file.Path;
import vn.edu.lanfileshare.server.storage.*;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.server.network.*;
/** TCP bootstrap with persistent, verified file metadata and upload support. */
public final class ServerMain {
    public static void main(String[] args) throws Exception {
        args = vn.edu.lanfileshare.server.cli.CliArguments.decode(args);
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 49000;
        String bind = args.length > 1 ? args[1] : "0.0.0.0";
        Path storageDirectory = Path.of(args.length > 2 ? args[2] : "runtime/server");
        FileStorageService storage = new FileStorageService(new StorageConfiguration(storageDirectory));
        JsonFileCatalog catalog = new JsonFileCatalog(storage);
        long maxFileBytes = args.length > 3 ? Long.parseLong(args[3]) : vn.edu.lanfileshare.server.transfer.UploadHandler.DEFAULT_MAX_FILE_BYTES;
        try (var uploads = new vn.edu.lanfileshare.server.transfer.UploadHandler(storage, catalog, maxFileBytes);
             TcpServer server = new TcpServer(InetAddress.getByName(bind), port, 32, 30_000, new RequestDispatcher(catalog, uploads))) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { server.close(); } catch (Exception ex) { System.getLogger(ServerMain.class.getName()).log(System.Logger.Level.ERROR, "Shutdown failed", ex); } }));
            System.out.println("LAN File Sharing TCP listening on " + bind + ":" + server.port());
            System.out.println("Persistent catalog and TCP upload ready.");
            server.serve();
        }
    }
}
