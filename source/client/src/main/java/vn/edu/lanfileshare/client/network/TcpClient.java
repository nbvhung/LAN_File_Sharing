package vn.edu.lanfileshare.client.network;

import java.io.IOException;
import java.net.*;
import java.util.UUID;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.*;

/** Sequential v1 connection. A failed exchange closes the connection; reconnect explicitly. */
public final class TcpClient implements AutoCloseable {
    private final int timeoutMillis;
    private volatile Socket socket;
    private String lastHost;
    private int lastPort;
    private volatile ClientState state = ClientState.DISCONNECTED;
    private final vn.edu.lanfileshare.common.io.PayloadMetrics metrics = new vn.edu.lanfileshare.common.io.PayloadMetrics();

    public ClientState state() {
        return state;
    }

    public vn.edu.lanfileshare.common.io.PayloadMetrics.Snapshot metrics() {
        return metrics.snapshot();
    }

    public TcpClient() {
        this(30_000);
    }

    public TcpClient(int timeoutMillis) {
        if (timeoutMillis < 1)
            throw new IllegalArgumentException("Timeout must be positive");
        this.timeoutMillis = timeoutMillis;
    }

    public synchronized void connect(String host, int port) throws IOException {
        if (socket != null)
            throw new IllegalStateException("Already connected");
        lastHost = host;
        lastPort = port;
        Socket candidate = new Socket();
        try {
            candidate.connect(new InetSocketAddress(host, port), timeoutMillis);
            candidate.setSoTimeout(timeoutMillis);
            candidate.setTcpNoDelay(true);
            socket = candidate;
            state = ClientState.CONNECTED;
        } catch (IOException | RuntimeException ex) {
            try {
                candidate.close();
            } catch (IOException close) {
                ex.addSuppressed(close);
            }
            state = ClientState.DISCONNECTED;
            throw ex;
        }
    }

    public void reconnect() throws IOException {
        reconnect(ReconnectPolicy.defaults());
    }

    public synchronized void reconnect(ReconnectPolicy policy) throws IOException {
        if (exchanging)
            throw new IllegalStateException("Cannot reconnect inside an exchange");
        if (lastHost == null)
            throw new IOException("No previous endpoint");
        closeSocket();
        String host = lastHost;
        int port = lastPort;
        IOException last = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            if (attempt > 1)
                policy.backoff(attempt - 1);
            try {
                connect(host, port);
                return;
            } catch (IOException ex) {
                last = ex;
            }
        }
        state = ClientState.FAILED;
        throw last;
    }

    public synchronized boolean isConnected() {
        return socket != null && !socket.isClosed();
    }

    @FunctionalInterface
    public interface ConnectionOperation<T> {
        T run(ClientConnection connection) throws IOException;
    }

    private boolean exchanging;

    /** Locks the complete multi-frame exchange, preventing v1 pipelining. Callbacks must not reenter. */
    public synchronized <T> T exchange(ConnectionOperation<T> operation) throws IOException {
        return perform(operation, false, false);
    }

    public synchronized <T> T transfer(boolean resuming, ConnectionOperation<T> operation) throws IOException {
        return perform(operation, true, resuming);
    }

    private <T> T perform(ConnectionOperation<T> operation, boolean transfer, boolean resume) throws IOException {
        if (!isConnected())
            throw new IOException("Client is disconnected");
        if (exchanging)
            throw new IllegalStateException("An exchange is already in progress");
        exchanging = true;
        state = transfer ? (resume ? ClientState.RESUMING : ClientState.TRANSFERRING) : ClientState.CONNECTED;
        try {
            T result = operation.run(new ClientConnection(socket, metrics));
            state = transfer ? ClientState.COMPLETED : ClientState.CONNECTED;
            return result;
        } catch (IOException | RuntimeException ex) {
            try {
                closeSocket();
            } catch (IOException close) {
                ex.addSuppressed(close);
            }
            state = TransferFailures.interrupted(ex) ? ClientState.INTERRUPTED : ClientState.FAILED;
            throw ex;
        } finally {
            exchanging = false;
        }
    }

    public FileList listFiles() throws IOException {
        return exchange(connection -> {
            UUID id = UUID.randomUUID();
            connection.send(ControlCodec.encode(MessageType.LIST_FILES, new ListFiles(1, id)));
            Frame response = connection.receive(id);
            if (response.type() != MessageType.FILE_LIST)
                throw new IOException("Expected FILE_LIST");
            return ControlCodec.decode(response, FileList.class);
        });
    }

    public synchronized void disconnect() throws IOException {
        closeSocket();
        state = ClientState.DISCONNECTED;
    }

    /** Close the current socket without waiting for an exchange's monitor (desktop stop/close).
     * The exchange still owns cleanup/state and reports its normal interruption checkpoint. */
    public void interruptConnection() throws IOException {
        Socket current = socket;
        if (current != null)
            current.close();
    }

    private void closeSocket() throws IOException {
        Socket previous = socket;
        socket = null;
        if (previous != null)
            previous.close();
    }

    @Override
    public void close() throws IOException {
        disconnect();
    }
}
