package vn.edu.lanfileshare.server.network;
import java.io.*;
import java.net.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
/** Bounded connection executor; each connection has exactly one reader. */
public final class TcpServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(TcpServer.class.getName());
    private final ServerSocket listener;
    private final ThreadPoolExecutor workers;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final RequestDispatcher dispatcher;
    private final int timeoutMillis;
    private final AtomicBoolean closed = new AtomicBoolean();
    public TcpServer(InetAddress bindAddress, int port, int maxConnections, int timeoutMillis, RequestDispatcher dispatcher) throws IOException {
        if (maxConnections < 1 || timeoutMillis < 1) throw new IllegalArgumentException("Invalid server limits");
        this.dispatcher = dispatcher; this.timeoutMillis = timeoutMillis;
        workers = new ThreadPoolExecutor(0, maxConnections, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
        listener = new ServerSocket();
        try { listener.bind(new InetSocketAddress(bindAddress, port)); }
        catch (IOException ex) { listener.close(); workers.shutdownNow(); throw ex; }
    }
    public int port() { return listener.getLocalPort(); }
    private Thread acceptThread;
    /** Start the accept loop asynchronously. Instances are single-use. */
    public synchronized void start() {
        if (closed.get() || acceptThread != null) throw new IllegalStateException("Server already started or stopped");
        acceptThread = new Thread(() -> {
            try { serve(); }
            catch (IOException ex) { LOG.log(System.Logger.Level.ERROR, "Accept loop failed", ex); }
        }, "lanfs-accept");
        acceptThread.start();
    }
    public void stop() throws IOException { close(); }
    public void serve() throws IOException {
        while (!closed.get()) {
            Socket socket;
            try { socket = listener.accept(); }
            catch (SocketException ex) { if (closed.get()) break; throw ex; }
            sockets.add(socket);
            if (closed.get()) { sockets.remove(socket); socket.close(); break; }
            try {
                socket.setSoTimeout(timeoutMillis);
                socket.setTcpNoDelay(true);
                workers.execute(new ClientSession(socket, dispatcher, () -> sockets.remove(socket)));
            } catch (RejectedExecutionException ex) {
                try (socket) {
                    LOG.log(System.Logger.Level.WARNING, "SERVER_BUSY: connection limit reached");
                } finally { sockets.remove(socket); }
            } catch (IOException ex) { LOG.log(System.Logger.Level.WARNING, "Connection setup failed", ex); sockets.remove(socket); socket.close(); }
        }
    }
    @Override public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        try { listener.close(); }
        finally {
            for (Socket socket : sockets) { try { socket.close(); } catch (IOException ex) { LOG.log(System.Logger.Level.WARNING, "Socket close failed", ex); } }
            workers.shutdownNow();
            try {
                if (!workers.awaitTermination(5, TimeUnit.SECONDS))
                    LOG.log(System.Logger.Level.WARNING, "Session workers still stopping");
                if (acceptThread != null && acceptThread != Thread.currentThread()) acceptThread.join(5000);
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
    }
}
