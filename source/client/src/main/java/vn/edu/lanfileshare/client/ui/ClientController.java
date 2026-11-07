package vn.edu.lanfileshare.client.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import vn.edu.lanfileshare.client.transfer.*;
import vn.edu.lanfileshare.client.network.ServerResponseException;
import vn.edu.lanfileshare.client.network.TransferFailures;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.model.Messages.*;

/** EDT owns presentation state; exactly one worker owns all service operations. */
public final class ClientController implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ClientController.class.getName());
    private final ClientBackend backend;
    private final ClientView view;
    private final ExecutorService worker = daemonExecutor("lanfs-ui-worker");
    private final ExecutorService control = daemonExecutor("lanfs-ui-control");
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> termination = new CompletableFuture<>();
    private final List<TransferItem> transfers = new ArrayList<>();
    private List<FileInfo> files = List.of();
    private Endpoint endpoint;
    private boolean connected, busy, disconnecting;
    private UiState state = UiState.DISCONNECTED;
    private String message = "Enter the server IP and TCP port to begin.";

    public ClientController(ClientBackend backend, ClientView view) {
        requireEdt();
        this.backend = Objects.requireNonNull(backend);
        this.view = Objects.requireNonNull(view);
        render();
    }

    private static ExecutorService daemonExecutor(String name) {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    public UiSnapshot snapshot() {
        requireEdt();
        return new UiSnapshot(state, connected, busy, closed.get(), endpoint, message, files, transfers);
    }

    public void connect(String host, String port) {
        requireEdt();
        if (!available())
            return;
        final Endpoint target;
        try {
            target = Endpoint.parse(host, port);
        } catch (IllegalArgumentException ex) {
            notice("Invalid server", ex.getMessage(), true);
            return;
        }
        files = List.of();
        endpoint = target;
        begin(UiState.CONNECTING, "Connecting to " + target + " ...");
        execute(() -> {
            backend.connect(target);
            return backend.listFiles();
        }, result -> {
            connected = true;
            files = result;
            finish(UiState.CONNECTED, fileSummary());
            view.log("Connected to " + target + "; " + fileSummary());
        }, error -> operationFailed("Cannot connect to server", error));
    }

    public void refresh() {
        requireEdt();
        if (!available() || !requireConnection())
            return;
        begin(UiState.CONNECTED, "Refreshing server files ...");
        execute(backend::listFiles, result -> {
            files = result;
            finish(UiState.CONNECTED, fileSummary());
            view.log(fileSummary());
        }, error -> operationFailed("Refresh failed", error));
    }

    public void upload(Path source) {
        requireEdt();
        if (!available() || !requireConnection())
            return;
        if (source == null)
            return;
        TransferItem item = new TransferItem(UUID.randomUUID(), null, source.getFileName().toString(), Direction.UPLOAD,
                endpoint, source.toAbsolutePath().normalize(), null, 0, 0, 0, 0, UiState.VERIFYING,
                "Checking source and calculating SHA-256 ...");
        transfers.add(item);
        runTransfer(item, false);
    }

    public void download(FileInfo file, Path destination) {
        requireEdt();
        if (!available() || !requireConnection())
            return;
        if (file == null) {
            notice("Select a file", "Select a server file before clicking Download.", false);
            return;
        }
        if (destination == null)
            return;
        TransferItem item = new TransferItem(UUID.randomUUID(), null, file.name(), Direction.DOWNLOAD, endpoint,
                destination.toAbsolutePath().normalize(), null, 0, file.size(), 0, 0, UiState.DOWNLOADING,
                "Requesting download ...");
        transfers.add(item);
        runTransfer(item, false, file.fileId());
    }

    public void resume(UUID rowId) {
        requireEdt();
        if (!available() || !requireConnection())
            return;
        TransferItem item = transfers.stream().filter(row -> row.rowId().equals(rowId)).findFirst().orElse(null);
        if (item == null || !item.resumable()) {
            notice("Resume unavailable", "Select an Interrupted transfer with a saved checkpoint.", false);
            return;
        }
        if (!item.endpoint().equals(endpoint)) {
            notice("Different server", "Reconnect to " + item.endpoint() + " before resuming this transfer.", true);
            return;
        }
        runTransfer(item, true);
    }

    private void runTransfer(TransferItem item, boolean resume) {
        runTransfer(item, resume, null);
    }

    private void runTransfer(TransferItem item, boolean resume, UUID fileId) {
        UiState starting = resume ? UiState.RESUMING : item.status();
        replace(item.progress(item.bytes(), item.total(), item.percentage(), 0, starting,
                resume ? "Validating checkpoint and resuming ..." : item.detail()));
        begin(starting, (resume ? "Resuming " : "Preparing ") + item.name());
        ProgressPump progress = new ProgressPump(item.rowId(), item.direction());
        execute(() -> {
            if (!resume && item.direction() == Direction.UPLOAD) {
                long size = java.nio.file.Files.size(item.localPath());
                post(() -> {
                    TransferItem current = find(item.rowId());
                    replace(current.progress(0, size, 0, 0, UiState.VERIFYING, "Calculating source SHA-256 ..."));
                    render();
                });
            }
            ClientBackend.Outcome result = resume ? backend.resume(item, progress::accept)
                    : item.direction() == Direction.UPLOAD ? backend.upload(item.localPath(), progress::accept)
                            : backend.download(fileId, item.localPath(), progress::accept);
            List<FileInfo> refreshed = null;
            String warning = null;
            if (item.direction() == Direction.UPLOAD) {
                try {
                    refreshed = backend.listFiles();
                } catch (IOException ex) {
                    warning = ErrorMessages.describe(ex);
                    LOG.log(System.Logger.Level.WARNING, "Post-upload refresh failed", ex);
                }
            }
            return new Finished(result, refreshed, warning, backend.connected());
        }, done -> {
            TransferItem current = find(item.rowId());
            replace(current.progress(done.result.file().size(), done.result.file().size(), 100,
                    current.bytesPerSecond(), UiState.COMPLETED, "SHA-256 verified").identity(done.result.transferId(),
                            current.checkpoint(), done.result.file().size(), UiState.COMPLETED, "SHA-256 verified"));
            connected = done.connected;
            if (done.files != null)
                files = done.files;
            finish(UiState.COMPLETED, item.name() + " completed. SHA-256 verified.");
            view.log(item.direction() + " completed: " + item.name() + " | transferId=" + done.result.transferId()
                    + " | sha256=" + done.result.file().sha256());
            if (done.warning != null)
                view.log("Upload completed, but refresh failed: " + done.warning);
            notice("Transfer completed", item.name() + "\nSHA-256 verified."
                    + (done.warning == null ? "" : "\nRefresh failed: " + done.warning), false);
        }, error -> {
            TransferItem current = find(item.rowId());
            if (error instanceof TransferInterruptedException interrupted) {
                replace(current.identity(interrupted.transferId(), interrupted.checkpoint(), interrupted.nextOffset(),
                        UiState.INTERRUPTED, "Progress saved. Reconnect to " + item.endpoint() + " and Resume."));
                connected = false;
                finish(UiState.INTERRUPTED, "Transfer interrupted; checkpoint saved.");
                view.log("Interrupted: " + item.name() + " | transferId=" + interrupted.transferId() + " | nextOffset="
                        + interrupted.nextOffset() + " | checkpoint=" + interrupted.checkpoint());
                notice("Transfer interrupted", ErrorMessages.describe(error), false);
            } else if (resume && item.resumable() && (TransferFailures.interrupted(error)
                    || error instanceof ServerResponseException response && response.code() == ErrorCode.SERVER_BUSY)) {
                // A rejected/timed-out resume query has not invalidated the old checkpoint.
                String detail = "Resume failed: " + ErrorMessages.describe(error);
                replace(current.progress(current.bytes(), current.total(), current.percentage(), 0, UiState.INTERRUPTED,
                        detail));
                finish(UiState.INTERRUPTED, detail);
                view.log(detail);
                notice("Resume failed", detail, true);
            } else {
                replace(current.progress(current.bytes(), current.total(), current.percentage(), 0, UiState.FAILED,
                        ErrorMessages.describe(error)));
                operationFailed(resume ? "Resume failed" : "Transfer failed", error);
            }
        });
    }

    private record Finished(ClientBackend.Outcome result, List<FileInfo> files, String warning, boolean connected) {
    }

    /** Disconnect closes the socket on a separate control thread, including while receive() holds the service lock. */
    public void disconnect() {
        requireEdt();
        if (closed.get() || disconnecting)
            return;
        if (busy) {
            disconnecting = true;
            message = "Disconnecting; preserving transfer progress ...";
            render();
            control.execute(() -> {
                try {
                    backend.interrupt();
                } catch (IOException ex) {
                    LOG.log(System.Logger.Level.WARNING, "Socket interruption failed", ex);
                }
            });
            return;
        }
        begin(UiState.DISCONNECTED, "Disconnecting ...");
        execute(() -> {
            backend.disconnect();
            return Boolean.TRUE;
        }, ignored -> {
            connected = false;
            files = List.of();
            finish(UiState.DISCONNECTED, "Disconnected. Transfer history was kept.");
            view.log("Disconnected.");
        }, error -> operationFailed("Disconnect failed", error));
    }

    private final class ProgressPump {
        private final UUID id;
        private final Direction direction;
        private long last, started, baseline;

        private ProgressPump(UUID id, Direction direction) {
            this.id = id;
            this.direction = direction;
        }

        void accept(TransferProgress progress) {
            long now = System.nanoTime();
            if (started == 0) {
                started = now;
                baseline = progress.bytesTransferred();
            }
            boolean important = progress.status() == TransferStatus.VERIFYING
                    || progress.status() == TransferStatus.COMPLETED;
            if (last != 0 && now - last < 80_000_000 && !important)
                return;
            last = now;
            double speed = Math.max(0, progress.bytesTransferred() - baseline) * 1_000_000_000.0
                    / Math.max(1, now - started);
            UiState next = important ? UiState.VERIFYING
                    : direction == Direction.UPLOAD ? UiState.UPLOADING : UiState.DOWNLOADING;
            double percent = Math.min(99.9, progress.percentage());
            post(() -> {
                TransferItem current = find(id);
                replace(current.progress(progress.bytesTransferred(), progress.totalBytes(), percent, speed, next,
                        important ? "Verifying SHA-256 and confirming completion ..."
                                : "Transferring binary data ..."));
                state = next;
                message = current.name() + " - " + next;
                render();
            });
        }
    }

    private <T> void execute(Callable<T> task, Consumer<T> success, Consumer<Exception> failure) {
        worker.execute(() -> {
            try {
                T result = task.call();
                post(() -> success.accept(result));
            } catch (Exception ex) {
                LOG.log(System.Logger.Level.WARNING, "Desktop operation failed", ex);
                // connected() is deliberately queried on the worker, never on the EDT.
                boolean live = backend.connected();
                post(() -> {
                    connected = live;
                    failure.accept(ex);
                });
            }
        });
    }

    private void post(Runnable action) {
        SwingUtilities.invokeLater(() -> {
            if (!closed.get())
                action.run();
        });
    }

    private void operationFailed(String title, Exception error) {
        finish(UiState.FAILED, ErrorMessages.describe(error));
        view.log(title + ": " + message);
        notice(title, message, true);
    }

    private boolean available() {
        return !closed.get() && !busy;
    }

    private boolean requireConnection() {
        if (connected)
            return true;
        notice("Not connected", "Connect to a server first.", false);
        return false;
    }

    private void begin(UiState next, String text) {
        busy = true;
        disconnecting = false;
        state = next;
        message = text;
        render();
    }

    private void finish(UiState next, String text) {
        busy = false;
        disconnecting = false;
        state = next;
        message = text;
        render();
    }

    private String fileSummary() {
        return files.isEmpty() ? "No files on this server." : files.size() + " completed file(s) available.";
    }

    private TransferItem find(UUID id) {
        return transfers.stream().filter(item -> item.rowId().equals(id)).findFirst().orElseThrow();
    }

    private void replace(TransferItem row) {
        for (int i = 0; i < transfers.size(); i++)
            if (transfers.get(i).rowId().equals(row.rowId())) {
                transfers.set(i, row);
                return;
            }
        throw new IllegalStateException("Unknown UI transfer row");
    }

    private void notice(String title, String text, boolean error) {
        view.notice(title, text, error);
    }

    private void render() {
        view.render(snapshot());
    }

    public CompletableFuture<Void> termination() {
        return termination;
    }

    @Override
    public void close() {
        requireEdt();
        if (!closed.compareAndSet(false, true))
            return;
        message = "Closing connection ...";
        render();
        // Interrupt the socket, not FileChannel writes: a thread interrupt could turn
        // a resumable disconnect into ClosedByInterruptException during checkpoint I/O.
        worker.shutdown();
        control.execute(() -> {
            try {
                backend.interrupt();
                backend.close();
                termination.complete(null);
            } catch (Exception ex) {
                LOG.log(System.Logger.Level.WARNING, "Desktop shutdown failed", ex);
                termination.completeExceptionally(ex);
            } finally {
                control.shutdown();
            }
        });
        view.closed();
    }

    static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("UI state must be accessed on the EDT");
    }
}
