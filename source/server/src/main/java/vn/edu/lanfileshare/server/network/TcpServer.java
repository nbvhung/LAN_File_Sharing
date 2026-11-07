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
    private final ThreadPoolExecutor overload = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), namedFactory("lanfs-overload"));
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1,
            namedFactory("lanfs-deadline"));
    private final Set<Socket> rejecting = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicLong rejected = new java.util.concurrent.atomic.AtomicLong();

    private static java.util.concurrent.ThreadFactory namedFactory(String prefix) {
        var sequence = new java.util.concurrent.atomic.AtomicInteger();
        return task -> new Thread(task, prefix + "-" + sequence.incrementAndGet());
    }

    public record Statistics(int activeConnections, int workerThreads, int largestWorkerPool, long rejectedConnections,
            boolean terminated) {
    }

    public Statistics statistics() {
        return new Statistics(sockets.size(), workers.getPoolSize(), workers.getLargestPoolSize(), rejected.get(),
                workers.isTerminated() && overload.isTerminated() && deadlines.isTerminated());
    }

    private final ThreadPoolExecutor workers;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final RequestDispatcher dispatcher;
    private final int timeoutMillis;
    private final vn.edu.lanfileshare.common.io.PayloadMetrics metrics = new vn.edu.lanfileshare.common.io.PayloadMetrics();

    public vn.edu.lanfileshare.common.io.PayloadMetrics.Snapshot metrics() {
        return metrics.snapshot();
    }

    private final AtomicBoolean closed = new AtomicBoolean();

    public TcpServer(InetAddress bindAddress, int port, int maxConnections, int timeoutMillis,
            RequestDispatcher dispatcher) throws IOException {
        if (maxConnections < 1 || timeoutMillis < 1)
            throw new IllegalArgumentException("Invalid server limits");
        this.dispatcher = dispatcher;
        this.timeoutMillis = timeoutMillis;
        workers = new ThreadPoolExecutor(0, maxConnections, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
                namedFactory("lanfs-session"));
        deadlines.setRemoveOnCancelPolicy(true);
        listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(bindAddress, port));
        } catch (IOException ex) {
            listener.close();
            workers.shutdownNow();
            overload.shutdownNow();
            deadlines.shutdownNow();
            throw ex;
        }
    }

    public int port() {
        return listener.getLocalPort();
    }

    private Thread acceptThread;

    /** Start the accept loop asynchronously. Instances are single-use. */
    public synchronized void start() {
        if (closed.get() || acceptThread != null)
            throw new IllegalStateException("Server already started or stopped");
        acceptThread = new Thread(() -> {
            try {
                serve();
            } catch (IOException ex) {
                LOG.log(System.Logger.Level.ERROR, "Accept loop failed", ex);
            }
        }, "lanfs-accept");
        acceptThread.start();
    }

    public void stop() throws IOException {
        close();
    }

    public void serve() throws IOException {
        while (!closed.get()) {
            Socket socket;
            try {
                socket = listener.accept();
            } catch (SocketException ex) {
                if (closed.get())
                    break;
                throw ex;
            }
            sockets.add(socket);
            if (closed.get()) {
                sockets.remove(socket);
                socket.close();
                break;
            }
            try {
                socket.setSoTimeout(timeoutMillis);
                socket.setTcpNoDelay(true);
                workers.execute(new ClientSession(socket, dispatcher, () -> sockets.remove(socket), metrics, deadlines,
                        timeoutMillis));
            } catch (RejectedExecutionException ex) {
                sockets.remove(socket);
                reject(socket);
            } catch (IOException ex) {
                LOG.log(System.Logger.Level.WARNING, "Connection setup failed", ex);
                sockets.remove(socket);
                socket.close();
            }
        }
    }

    private void reject(Socket socket) {
        rejected.incrementAndGet();
        rejecting.add(socket);
        try {
            // Absolute deadline bounds reads AND writes, even slow-drip peers.
            var deadline = deadlines.schedule(() -> closeQuietly(socket), 500, TimeUnit.MILLISECONDS);
            try {
                overload.execute(() -> {
                    try (socket) {
                        socket.setSoTimeout(400);
                        var frame = vn.edu.lanfileshare.common.protocol.FrameCodec.read(socket.getInputStream());
                        if (frame.type() != vn.edu.lanfileshare.common.protocol.MessageType.FILE_CHUNK) {
                            vn.edu.lanfileshare.common.protocol.ControlCodec.validate(frame);
                            var request = vn.edu.lanfileshare.common.protocol.ControlCodec.requestId(frame);
                            RequestDispatcher.error(new SessionContext(socket.getOutputStream()), request,
                                    vn.edu.lanfileshare.common.error.ErrorCode.SERVER_BUSY,
                                    "Server connection limit reached");
                        }
                    } catch (IOException | RuntimeException ex) {
                        LOG.log(System.Logger.Level.DEBUG,
                                "Overload peer closed without correlated response: " + ex.getClass().getSimpleName());
                    } finally {
                        deadline.cancel(false);
                        rejecting.remove(socket);
                    }
                });
            } catch (RejectedExecutionException ex) {
                deadline.cancel(false);
                rejecting.remove(socket);
                closeQuietly(socket);
            }
        } catch (RejectedExecutionException ex) {
            rejecting.remove(socket);
            closeQuietly(socket);
        }
        LOG.log(System.Logger.Level.WARNING,
                "action=reject remote=" + socket.getRemoteSocketAddress() + " code=SERVER_BUSY");
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ex) {
            LOG.log(System.Logger.Level.WARNING, "Socket close failed", ex);
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true))
            return;
        try {
            listener.close();
        } finally {
            for (Socket socket : sockets) {
                try {
                    socket.close();
                } catch (IOException ex) {
                    LOG.log(System.Logger.Level.WARNING, "Socket close failed", ex);
                }
            }
            rejecting.forEach(TcpServer::closeQuietly);
            workers.shutdown();
            overload.shutdown();
            deadlines.shutdownNow();
            try {
                if (!workers.awaitTermination(5, TimeUnit.SECONDS))
                    LOG.log(System.Logger.Level.WARNING, "Session workers still stopping");
                overload.awaitTermination(2, TimeUnit.SECONDS);
                deadlines.awaitTermination(2, TimeUnit.SECONDS);
                if (acceptThread != null && acceptThread != Thread.currentThread())
                    acceptThread.join(5000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
