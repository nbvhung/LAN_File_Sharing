package vn.edu.lanfileshare.server.network;

import java.io.*;
import java.net.Socket;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.protocol.*;

/** Owns the only reader and the lifecycle of one accepted TCP socket. */
public final class ClientSession implements Runnable {
    private static final System.Logger LOG = System.getLogger(ClientSession.class.getName());
    private final Socket socket;
    private final RequestDispatcher dispatcher;
    private final Runnable onClosed;
    public ClientSession(Socket socket, RequestDispatcher dispatcher, Runnable onClosed) {
        this.socket = socket; this.dispatcher = dispatcher; this.onClosed = onClosed;
    }
    @Override public void run() {
        LOG.log(System.Logger.Level.INFO, "Connected: " + socket.getRemoteSocketAddress());
        SessionContext session = null;
        try (socket) {
            session = new SessionContext(socket.getOutputStream());
            PushbackInputStream input = new PushbackInputStream(new BufferedInputStream(socket.getInputStream()));
            while (!socket.isClosed()) {
                int first = input.read();
                if (first == -1) break;
                input.unread(first);
                Frame frame = FrameCodec.read(input);
                try { dispatcher.dispatch(session, frame); }
                catch (ControlCodec.UnsupportedVersionException ex) {
                    RequestDispatcher.error(session, dispatcher.requestId(session, frame), ErrorCode.UNSUPPORTED_VERSION, "Protocol version 1 required"); break;
                } catch (ProtocolException ex) {
                    if (ex.code() == ErrorCode.INVALID_FRAME) throw ex;
                    RequestDispatcher.error(session, dispatcher.requestId(session, frame), ex.code(), ex.getMessage());
                    if (ex.fatal()) break;
                } catch (IOException ex) {
                    LOG.log(System.Logger.Level.WARNING, "Service I/O failure", ex);
                    RequestDispatcher.error(session, dispatcher.requestId(session, frame), ErrorCode.IO_ERROR, "Service I/O failure");
                    break;
                }
            }
        } catch (IOException | RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Session ended (INVALID_FRAME, timeout or I/O): " + ex.getClass().getSimpleName());
        } finally {
            onClosed.run();
            LOG.log(System.Logger.Level.INFO, "Disconnected: " + socket.getRemoteSocketAddress());
            if (session != null) {
                try { dispatcher.disconnected(session.id()); }
                catch (RuntimeException ex) { LOG.log(System.Logger.Level.WARNING, "Session cleanup failed", ex); }
            }
        }
    }
}
