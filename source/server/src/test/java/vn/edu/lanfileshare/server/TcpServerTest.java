package vn.edu.lanfileshare.server;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import vn.edu.lanfileshare.server.network.*;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.*;
class TcpServerTest {
    @Test void realSocketsListFilesAndSurviveMalformedPeer() throws Exception {
        // Initialize the JVM entropy source before starting socket deadlines.
        UUID.randomUUID();
        RequestDispatcher dispatcher = new RequestDispatcher(List::of, (session, frame) -> fail("Unexpected transfer"));
        ExecutorService executor = Executors.newCachedThreadPool();
        try (TcpServer server = new TcpServer(InetAddress.getLoopbackAddress(), 0, 8, 2000, dispatcher)) {
          try {
            Future<?> serving = executor.submit(() -> { try { server.serve(); } catch (IOException ex) { throw new UncheckedIOException(ex); } });
            try (Socket bad = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
                new DataOutputStream(bad.getOutputStream()).writeInt(-1);
                bad.setSoTimeout(2000);
                assertEquals(-1, bad.getInputStream().read());
            }
            List<Socket> sockets = new ArrayList<>();
            try {
                for (int i = 0; i < 4; i++) sockets.add(new Socket(InetAddress.getLoopbackAddress(), server.port()));
                List<Future<?>> requests = new ArrayList<>();
                for (Socket socket : sockets) requests.add(executor.submit(() -> {
                    try {
                        socket.setSoTimeout(2000);
                        UUID id = UUID.randomUUID();
                        FrameCodec.write(socket.getOutputStream(), ControlCodec.encode(MessageType.LIST_FILES, new ListFiles(1, id)));
                        Frame response = FrameCodec.read(socket.getInputStream());
                        assertEquals(MessageType.FILE_LIST, response.type());
                        FileList list = ControlCodec.decode(response, FileList.class);
                        assertEquals(id, list.requestId()); assertTrue(list.files().isEmpty());
                    } catch (IOException ex) { throw new UncheckedIOException(ex); }
                }));
                for (Future<?> request : requests) request.get(5, TimeUnit.SECONDS);
            } finally { for (Socket socket : sockets) socket.close(); server.close(); }
            serving.get(5, TimeUnit.SECONDS);
          } finally { server.close(); }
        } finally { executor.shutdownNow(); }
    }
}
