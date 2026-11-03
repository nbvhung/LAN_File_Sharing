package vn.edu.lanfileshare.server;
import java.net.InetAddress;
import java.util.List;
import java.util.UUID;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.server.network.*;
/** Bootstrap using empty catalog until TV2 supplies storage/transfer implementations. */
public final class ServerMain {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 49000;
        String bind = args.length > 1 ? args[1] : "0.0.0.0";
        RequestDispatcher dispatcher = new RequestDispatcher(List::of, (session, frame) ->
            RequestDispatcher.error(session, vn.edu.lanfileshare.common.protocol.ControlCodec.requestId(frame), ErrorCode.BAD_REQUEST, "Transfer service is not installed yet"));
        try (TcpServer server = new TcpServer(InetAddress.getByName(bind), port, 32, 30_000, dispatcher)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { server.close(); } catch (Exception ex) { System.getLogger(ServerMain.class.getName()).log(System.Logger.Level.ERROR, "Shutdown failed", ex); } }));
            System.out.println("LAN File Sharing TCP listening on " + bind + ":" + server.port());
            System.out.println("Skeleton mode: LIST_FILES returns []; file transfer is not implemented yet.");
            server.serve();
        }
    }
}
