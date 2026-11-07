package vn.edu.lanfileshare.server.network;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.*;

/** Absolute frame-read/write deadline; SO_TIMEOUT alone cannot bound slow-drip reads or writes. */
final class SocketDeadline {
    private final Socket socket;
    private final ScheduledExecutorService timer;
    private final int timeout;

    SocketDeadline(Socket socket, ScheduledExecutorService timer, int timeout) {
        this.socket = socket;
        this.timer = timer;
        this.timeout = timeout;
    }

    @FunctionalInterface
    interface IO<T> {
        T run() throws IOException;
    }

    <T> T within(IO<T> action) throws IOException {
        if (timer == null)
            return action.run();
        ScheduledFuture<?> guard = timer.schedule(() -> {
            try {
                socket.close();
            } catch (IOException ex) {
                System.getLogger(SocketDeadline.class.getName()).log(System.Logger.Level.WARNING,
                        "Deadline socket close failed", ex);
            }
        }, timeout, TimeUnit.MILLISECONDS);
        try {
            return action.run();
        } finally {
            guard.cancel(false);
        }
    }

    OutputStream output(OutputStream stream) {
        return new FilterOutputStream(stream) {
            @Override
            public void write(int value) throws IOException {
                within(() -> {
                    out.write(value);
                    return null;
                });
            }

            @Override
            public void write(byte[] data, int offset, int length) throws IOException {
                within(() -> {
                    out.write(data, offset, length);
                    return null;
                });
            }
        };
    }
}
