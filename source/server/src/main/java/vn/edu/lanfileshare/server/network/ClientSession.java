package vn.edu.lanfileshare.server.network;

import java.io.*;
import java.net.Socket;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.protocol.*;

/** Owns the only reader and the lifecycle of one accepted TCP socket. */
public final class ClientSession implements Runnable {
    private static final System.Logger LOG = System.getLogger(ClientSession.class.getName());
    private final Socket socket;
    private final SocketDeadline deadline;
    private final RequestDispatcher dispatcher;
    private final Runnable onClosed;
    private final vn.edu.lanfileshare.common.io.PayloadMetrics metrics;

    public ClientSession(Socket socket, RequestDispatcher dispatcher, Runnable onClosed) {
        this(socket, dispatcher, onClosed, new vn.edu.lanfileshare.common.io.PayloadMetrics());
    }

    public ClientSession(Socket socket, RequestDispatcher dispatcher, Runnable onClosed,
            vn.edu.lanfileshare.common.io.PayloadMetrics metrics) {
        this(socket, dispatcher, onClosed, metrics, null, 30_000);
    }

    public ClientSession(Socket socket, RequestDispatcher dispatcher, Runnable onClosed,
            vn.edu.lanfileshare.common.io.PayloadMetrics metrics, java.util.concurrent.ScheduledExecutorService timer,
            int timeout) {
        deadline = new SocketDeadline(socket, timer, timeout);
        this.socket = socket;
        this.dispatcher = dispatcher;
        this.onClosed = onClosed;
        this.metrics = metrics;
    }

    @Override
    public void run() {
        long started = System.nanoTime();
        LOG.log(System.Logger.Level.INFO, "Connected: " + socket.getRemoteSocketAddress());
        SessionContext session = null;
        try (socket) {
            session = new SessionContext(deadline.output(socket.getOutputStream()), metrics);
            LOG.log(System.Logger.Level.INFO,
                    "sessionId=" + session.id() + " action=connect remote=" + socket.getRemoteSocketAddress());
            PushbackInputStream input = new PushbackInputStream(new BufferedInputStream(socket.getInputStream()));
            while (!socket.isClosed()) {
                int first = input.read();
                if (first == -1)
                    break;
                input.unread(first);
                Frame frame = deadline.within(() -> FrameCodec.read(input));
                session.received(frame);
                try {
                    dispatcher.dispatch(session, frame);
                } catch (ControlCodec.UnsupportedVersionException ex) {
                    RequestDispatcher.error(session, dispatcher.requestId(session, frame),
                            ErrorCode.UNSUPPORTED_VERSION, "Protocol version 1 required");
                    break;
                } catch (ProtocolException ex) {
                    if (ex.code() == ErrorCode.INVALID_FRAME)
                        throw ex;
                    RequestDispatcher.error(session, dispatcher.requestId(session, frame), ex.code(), ex.getMessage());
                    if (ex.fatal())
                        break;
                } catch (IOException ex) {
                    LOG.log(System.Logger.Level.WARNING, "Service I/O failure", ex);
                    RequestDispatcher.error(session, dispatcher.requestId(session, frame), ErrorCode.IO_ERROR,
                            "Service I/O failure");
                    break;
                } catch (RuntimeException ex) {
                    LOG.log(System.Logger.Level.ERROR,
                            "sessionId=" + session.id() + " action=dispatch code=INTERNAL_ERROR", ex);
                    RequestDispatcher.error(session, dispatcher.requestId(session, frame), ErrorCode.INTERNAL_ERROR,
                            "Internal service failure");
                    break;
                }
            }
        } catch (IOException | RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING,
                    "Session ended (INVALID_FRAME, timeout or I/O): " + ex.getClass().getSimpleName());
        } finally {
            LOG.log(System.Logger.Level.INFO, "Disconnected: " + socket.getRemoteSocketAddress());
            if (session != null) {
                try {
                    dispatcher.disconnected(session.id());
                } catch (RuntimeException ex) {
                    LOG.log(System.Logger.Level.WARNING, "Session cleanup failed", ex);
                }
            }
            LOG.log(System.Logger.Level.INFO,
                    "sessionId=" + (session == null ? "uninitialized" : session.id()) + " action=disconnect elapsedMs="
                            + (System.nanoTime() - started) / 1_000_000 + " "
                            + (session == null ? "" : session.counters()));
            onClosed.run();
        }
    }
}
