package vn.edu.lanfileshare.client.network;

import java.io.IOException;
import java.net.*;
import java.util.UUID;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.*;

/** Sequential v1 connection. A failed exchange closes the connection; reconnect explicitly. */
public final class TcpClient implements AutoCloseable {
    private final int timeoutMillis;
    private Socket socket;
    public TcpClient() { this(30_000); }
    public TcpClient(int timeoutMillis) {
        if (timeoutMillis < 1) throw new IllegalArgumentException("Timeout must be positive");
        this.timeoutMillis = timeoutMillis;
    }
    public synchronized void connect(String host, int port) throws IOException {
        if (socket != null) throw new IllegalStateException("Already connected");
        Socket candidate = new Socket();
        try {
            candidate.connect(new InetSocketAddress(host, port), timeoutMillis);
            candidate.setSoTimeout(timeoutMillis);
            candidate.setTcpNoDelay(true);
            socket = candidate;
        } catch (IOException | RuntimeException ex) {
            try { candidate.close(); } catch (IOException close) { ex.addSuppressed(close); }
            throw ex;
        }
    }
    public synchronized boolean isConnected() { return socket != null && !socket.isClosed(); }
    @FunctionalInterface public interface ConnectionOperation<T> {
        T run(ClientConnection connection) throws IOException;
    }
    private boolean exchanging;
    /** Locks the complete multi-frame exchange, preventing v1 pipelining. Callbacks must not reenter. */
    public synchronized <T> T exchange(ConnectionOperation<T> operation) throws IOException {
        if (!isConnected()) throw new IOException("Client is disconnected");
        if (exchanging) throw new IllegalStateException("An exchange is already in progress");
        exchanging = true;
        try { return operation.run(new ClientConnection(socket)); }
        catch (IOException | RuntimeException ex) {
            try { disconnect(); } catch (IOException close) { ex.addSuppressed(close); }
            throw ex;
        } finally { exchanging = false; }
    }
    public FileList listFiles() throws IOException {
        return exchange(connection -> {
            UUID id = UUID.randomUUID();
            connection.send(ControlCodec.encode(MessageType.LIST_FILES, new ListFiles(1, id)));
            Frame response = connection.receive(id);
            if (response.type() != MessageType.FILE_LIST) throw new IOException("Expected FILE_LIST");
            return ControlCodec.decode(response, FileList.class);
        });
    }
    public synchronized void disconnect() throws IOException {
        Socket previous = socket;
        socket = null;
        if (previous != null) previous.close();
    }
    @Override public void close() throws IOException { disconnect(); }
}
