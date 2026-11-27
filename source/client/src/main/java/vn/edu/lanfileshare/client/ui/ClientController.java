package vn.edu.lanfileshare.client.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import vn.edu.lanfileshare.client.transfer.TransferInterruptedException;
import vn.edu.lanfileshare.client.transfer.TransferProgress;
import vn.edu.lanfileshare.client.transfer.TransferStatus;
import vn.edu.lanfileshare.client.discovery.DiscoveredServer;
import vn.edu.lanfileshare.client.network.ServerResponseException;
import vn.edu.lanfileshare.client.network.TransferFailures;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.model.Messages.Direction;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;

/** Điều phối kết nối, tìm server và truyền file. EDT giữ trạng thái hiển thị, worker gọi dịch vụ. */
public final class ClientController implements AutoCloseable {
    private static final long PROGRESS_UPDATE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(80);
    private static final System.Logger LOG = System.getLogger(ClientController.class.getName());
    private final ClientBackend backend;
    private final ClientView view;
    private final ServerDiscovery discovery;
    private final AtomicBoolean discoveryCancelled = new AtomicBoolean();
    private CompletableFuture<Void> discoveryCancellation = CompletableFuture.completedFuture(null);
    private List<DiscoveredServer> discoveredServers = List.of();
    private DiscoveredServer selectedServer;
    private final ExecutorService worker = daemonExecutor("lanfs-ui-worker");
    // Dùng luồng riêng để hủy hoặc ngắt ngay cả khi worker đang chờ socket.
    private final ExecutorService control = daemonExecutor("lanfs-ui-control");
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> shutdownCompletion = new CompletableFuture<>();
    private final List<TransferItem> transfers = new ArrayList<>();
    private List<FileInfo> files = List.of();
    private Endpoint endpoint;
    private boolean connected;
    private boolean busy;
    private boolean disconnecting;
    private UiState state = UiState.DISCONNECTED;
    private String statusMessage = "Enter the server IP and TCP port to begin.";

    public ClientController(ClientBackend backend, ClientView view) {
        this(backend, new ServiceServerDiscovery(), view);
    }

    public ClientController(ClientBackend backend, ServerDiscovery discovery, ClientView view) {
        requireEdt();
        this.discovery = Objects.requireNonNull(discovery);
        this.backend = Objects.requireNonNull(backend);
        this.view = Objects.requireNonNull(view);
        renderSnapshot();
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
        return new UiSnapshot(state, connected, busy, closed.get(), endpoint, statusMessage, files, transfers,
                discoveredServers, selectedServer);
    }

    /** Tìm server qua UDP để người dùng chọn địa chỉ trước khi kết nối TCP. */
    public void discover(String udpPort) {
        requireEdt();
        if (!canStartOperation() || connected) {
            return;
        }
        final int port;
        try {
            port = Endpoint.requireValidPort(Integer.parseInt(udpPort.strip()), "UDP");
        } catch (IllegalArgumentException ex) {
            view.notice("Invalid UDP port", "UDP port must be a number between 1 and 65535.", true);
            return;
        }
        discoveredServers = List.of();
        selectedServer = null;
        discoveryCancelled.set(false);
        discoveryCancellation = CompletableFuture.completedFuture(null);
        startOperation(UiState.DISCOVERING, "Discovering LAN servers ... IP/port entry remains available after the search.");
        executeInBackground(() -> {
            if (discoveryCancelled.get()) {
                throw new CancellationException("Discovery cancelled");
            }
            return discovery.discover(port);
        }, results -> {
            if (discoveryCancelled.get()) {
                finishCancelledDiscovery();
                return;
            }
            discoveredServers = List.copyOf(results);
            finishOperation(UiState.DISCONNECTED, results.isEmpty()
                    ? "No servers found. You can still enter an IP and TCP port manually."
                    : "Found " + results.size() + " server(s). Select one, then click Connect.");
            view.log(statusMessage);
        }, error -> {
            if (discoveryCancelled.get()) {
                finishCancelledDiscovery();
                return;
            }
            finishOperation(UiState.FAILED, "Discovery failed. Enter an IP and TCP port manually. "
                    + ErrorMessages.describe(error));
            view.log(statusMessage);
            view.notice("Discovery unavailable", statusMessage, true);
        });
    }

    public void selectServer(DiscoveredServer server) {
        requireEdt();
        if (!canStartOperation() || connected) {
            return;
        }
        if (server != null && !discoveredServers.contains(server)) {
            view.notice("Server unavailable", "Select a server from the current discovery results.", false);
            return;
        }
        selectedServer = server;
        statusMessage = server == null ? "Choose a discovered server or enter an IP manually."
                : "Selected " + server.host() + ":" + server.tcpPort() + ". Click Connect to confirm.";
        renderSnapshot();
    }

    /** Gửi lệnh hủy sang luồng điều khiển để lượt tìm có thể thoát khi đang chờ UDP. */
    public void cancelDiscovery() {
        requireEdt();
        if (closed.get() || !busy || state != UiState.DISCOVERING || discoveryCancelled.get()) {
            return;
        }
        CompletableFuture<Void> cancelled = new CompletableFuture<>();
        discoveryCancellation = cancelled;
        discoveryCancelled.set(true);
        statusMessage = "Cancelling discovery ...";
        renderSnapshot();
        control.execute(() -> {
            try {
                discovery.cancel();
                cancelled.complete(null);
            } catch (RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING, "Discovery cancellation failed", ex);
                cancelled.completeExceptionally(ex);
            }
        });
    }

    private void finishCancelledDiscovery() {
        // Chỉ mở lượt tìm mới sau khi lần hủy hiện tại hoàn tất.
        // Nếu tìm quá sớm, lệnh hủy cũ có thể đóng socket của lượt mới.
        discoveryCancellation.whenComplete((ignored, error) -> enqueueOnEdt(() -> {
            discoveredServers = List.of();
            selectedServer = null;
            if (error == null) {
                finishOperation(UiState.DISCONNECTED, "Discovery cancelled. Manual IP/port connection is available.");
            } else {
                finishOperation(UiState.FAILED,
                        "Discovery cancellation failed. You can still connect by IP/port manually.");
            }
            view.log(statusMessage);
        }));
    }

    /** Mở kết nối TCP rồi lấy danh sách file để hiển thị ngay khi kết nối xong. */
    public void connect(String host, String port) {
        requireEdt();
        if (!canStartOperation()) {
            return;
        }
        final Endpoint target;
        try {
            target = Endpoint.parse(host, port);
        } catch (IllegalArgumentException ex) {
            view.notice("Invalid server", ex.getMessage(), true);
            return;
        }
        files = List.of();
        endpoint = target;
        startOperation(UiState.CONNECTING, "Connecting to " + target + " ...");
        executeInBackground(() -> {
            backend.connect(target);
            return backend.listFiles();
        }, result -> {
            connected = true;
            files = result;
            finishOperation(UiState.CONNECTED, fileSummary());
            view.log("Connected to " + target + "; " + fileSummary());
        }, error -> operationFailed("Cannot connect to server", error));
    }

    public void refresh() {
        requireEdt();
        if (!canStartOperation() || !ensureConnected()) {
            return;
        }
        startOperation(UiState.CONNECTED, "Refreshing server files ...");
        executeInBackground(backend::listFiles, result -> {
            files = result;
            finishOperation(UiState.CONNECTED, fileSummary());
            view.log(fileSummary());
        }, error -> operationFailed("Refresh failed", error));
    }

    public void upload(Path source) {
        requireEdt();
        if (!canStartOperation() || !ensureConnected()) {
            return;
        }
        if (source == null) {
            return;
        }
        TransferItem item = TransferItem.forUpload(endpoint, source);
        transfers.add(item);
        runTransfer(item, false, null);
    }

    public void download(FileInfo file, Path destination) {
        requireEdt();
        if (!canStartOperation() || !ensureConnected()) {
            return;
        }
        if (file == null) {
            view.notice("Select a file", "Select a server file before clicking Download.", false);
            return;
        }
        if (destination == null) {
            return;
        }
        TransferItem item = TransferItem.forDownload(endpoint, file, destination);
        transfers.add(item);
        runTransfer(item, false, file.fileId());
    }

    /** Tiếp tục từ checkpoint sau khi đã kết nối lại đúng server. */
    public void resume(UUID rowId) {
        requireEdt();
        if (!canStartOperation() || !ensureConnected()) {
            return;
        }
        TransferItem item = findTransferByRowId(rowId);
        if (item == null || !item.canResume()) {
            view.notice("Resume unavailable", "Select an Interrupted transfer with a saved checkpoint.", false);
            return;
        }
        if (!item.endpoint().equals(endpoint)) {
            view.notice("Different server", "Reconnect to " + item.endpoint() + " before resuming this transfer.", true);
            return;
        }
        runTransfer(item, true, null);
    }

    /** Cập nhật trạng thái hàng trước khi giao lượt truyền cho worker. */
    private void runTransfer(TransferItem item, boolean resume, UUID downloadFileId) {
        if (resume) {
            updateTransferItem(item.withProgress(item.transferredBytes(), item.totalBytes(), item.percentage(), 0,
                    UiState.RESUMING, "Validating checkpoint and resuming ..."));
            startOperation(UiState.RESUMING, "Resuming " + item.fileName());
        } else {
            startOperation(item.status(), "Preparing " + item.fileName());
        }

        ProgressPump progress = new ProgressPump(item.rowId(), item.direction());
        executeInBackground(() -> performTransfer(item, resume, downloadFileId, progress),
                result -> transferSucceeded(item, result),
                error -> transferFailed(item, resume, error));
    }

    /** Gọi dịch vụ trên worker và thử tải lại danh sách file sau upload. */
    private TransferCompletion performTransfer(TransferItem item, boolean resume, UUID downloadFileId,
            ProgressPump progress) throws IOException {
        ClientBackend.Outcome outcome;
        if (resume) {
            outcome = backend.resume(item, progress::accept);
        } else if (item.direction() == Direction.UPLOAD) {
            long size = Files.size(item.localPath());
            enqueueOnEdt(() -> {
                TransferItem current = findTransferByRowId(item.rowId());
                updateTransferItem(current.withProgress(0, size, 0, 0, UiState.VERIFYING,
                        "Calculating source SHA-256 ..."));
                renderSnapshot();
            });
            outcome = backend.upload(item.localPath(), progress::accept);
        } else {
            outcome = backend.download(downloadFileId, item.localPath(), progress::accept);
        }

        List<FileInfo> refreshedFiles = null;
        String refreshWarning = null;
        if (item.direction() == Direction.UPLOAD) {
            try {
                refreshedFiles = backend.listFiles();
            } catch (IOException ex) {
                // Upload đã thành công. Chỉ báo riêng lỗi làm mới danh sách.
                refreshWarning = ErrorMessages.describe(ex);
                LOG.log(System.Logger.Level.WARNING, "Post-upload refresh failed", ex);
            }
        }
        return new TransferCompletion(outcome, refreshedFiles, refreshWarning, backend.connected());
    }

    /** Đánh dấu hoàn tất sau khi dịch vụ xác nhận kết quả. */
    private void transferSucceeded(TransferItem item, TransferCompletion completion) {
        TransferItem current = findTransferByRowId(item.rowId());
        ClientBackend.Outcome outcome = completion.outcome();
        long size = outcome.file().size();
        String verified = "SHA-256 verified";
        updateTransferItem(current.completed(outcome.transferId(), size, verified));

        connected = completion.connected();
        if (completion.refreshedFiles() != null) {
            files = completion.refreshedFiles();
        }
        finishOperation(UiState.COMPLETED, item.fileName() + " completed. SHA-256 verified.");
        view.log(item.direction() + " completed: " + item.fileName() + " | transferId=" + outcome.transferId()
                + " | sha256=" + outcome.file().sha256());

        String noticeText = item.fileName() + "\nSHA-256 verified.";
        if (completion.refreshWarning() != null) {
            view.log("Upload completed, but refresh failed: " + completion.refreshWarning());
            noticeText += "\nRefresh failed: " + completion.refreshWarning();
        }
        view.notice("Transfer completed", noticeText, false);
    }

    /** Giữ lại hàng có thể Resume nếu dịch vụ đã lưu checkpoint. */
    private void transferFailed(TransferItem item, boolean resume, Exception error) {
        TransferItem current = findTransferByRowId(item.rowId());
        if (error instanceof TransferInterruptedException interrupted) {
            String detail = "Progress saved. Reconnect to " + item.endpoint() + " and Resume.";
            updateTransferItem(current.interruptedAt(interrupted.transferId(), interrupted.checkpoint(),
                    interrupted.nextOffset(), detail));
            connected = false;
            finishOperation(UiState.INTERRUPTED, "Transfer interrupted; checkpoint saved.");
            view.log("Interrupted: " + item.fileName() + " | transferId=" + interrupted.transferId() + " | nextOffset="
                    + interrupted.nextOffset() + " | checkpoint=" + interrupted.checkpoint());
            view.notice("Transfer interrupted", ErrorMessages.describe(error), false);
            return;
        }

        boolean serverBusy = error instanceof ServerResponseException response
                && response.code() == ErrorCode.SERVER_BUSY;
        if (resume && item.canResume() && (TransferFailures.interrupted(error) || serverBusy)) {
            // Lỗi mạng hoặc server bận khi hỏi resume vẫn cho phép thử lại với checkpoint cũ.
            String detail = "Resume failed: " + ErrorMessages.describe(error);
            updateTransferItem(current.withProgress(current.transferredBytes(), current.totalBytes(), current.percentage(),
                    0, UiState.INTERRUPTED, detail));
            finishOperation(UiState.INTERRUPTED, detail);
            view.log(detail);
            view.notice("Resume failed", detail, true);
            return;
        }

        updateTransferItem(current.withProgress(current.transferredBytes(), current.totalBytes(), current.percentage(),
                0, UiState.FAILED, ErrorMessages.describe(error)));
        operationFailed(resume ? "Resume failed" : "Transfer failed", error);
    }

    private record TransferCompletion(ClientBackend.Outcome outcome, List<FileInfo> refreshedFiles, String refreshWarning,
            boolean connected) {
    }

    /** Ngắt qua luồng điều khiển nếu worker đang bận với TCP. Khi rảnh, worker tự đóng phiên. */
    public void disconnect() {
        requireEdt();
        if (closed.get() || disconnecting) {
            return;
        }
        if (busy && state == UiState.DISCOVERING) {
            cancelDiscovery();
            return;
        }
        if (busy) {
            disconnecting = true;
            statusMessage = "Disconnecting; preserving transfer progress ...";
            renderSnapshot();
            control.execute(() -> {
                try {
                    backend.interrupt();
                } catch (IOException ex) {
                    LOG.log(System.Logger.Level.WARNING, "Socket interruption failed", ex);
                }
            });
            return;
        }
        startOperation(UiState.DISCONNECTED, "Disconnecting ...");
        executeInBackground(() -> {
            backend.disconnect();
            return null; // Ngắt kết nối không có dữ liệu trả về.
        }, ignored -> {
            connected = false;
            files = List.of();
            finishOperation(UiState.DISCONNECTED, "Disconnected. Transfer history was kept.");
            view.log("Disconnected.");
        }, error -> operationFailed("Disconnect failed", error));
    }

    /** Nhận callback tiến độ từ dịch vụ truyền trên worker. */
    private final class ProgressPump {
        private final UUID rowId;
        private final Direction direction;
        private long lastUpdateNanos;
        private long startedNanos;
        private long startingBytes;

        private ProgressPump(UUID rowId, Direction direction) {
            this.rowId = rowId;
            this.direction = direction;
        }

        void accept(TransferProgress progress) {
            long now = System.nanoTime();
            if (startedNanos == 0) {
                startedNanos = now;
                startingBytes = progress.bytesTransferred();
            }
            boolean verificationUpdate = progress.status() == TransferStatus.VERIFYING
                    || progress.status() == TransferStatus.COMPLETED;
            // Giới hạn lần vẽ thường ở 80 ms để nút bấm vẫn phản hồi. Giai đoạn xác minh được vẽ ngay.
            if (!verificationUpdate && lastUpdateNanos != 0 && now - lastUpdateNanos < PROGRESS_UPDATE_INTERVAL_NANOS) {
                return;
            }
            lastUpdateNanos = now;
            // Trừ số byte của callback đầu để tốc độ không tính cả phần đã lưu trước khi Resume.
            long bytesSinceStart = Math.max(0, progress.bytesTransferred() - startingBytes);
            // Đổi nanosecond sang giây. Chặn mẫu số ở ít nhất 1 ns khi hai mốc thời gian trùng nhau.
            long elapsedNanos = Math.max(1, now - startedNanos);
            double elapsedSeconds = (double) elapsedNanos / TimeUnit.SECONDS.toNanos(1);
            double bytesPerSecond = bytesSinceStart / elapsedSeconds;
            UiState transferState = direction == Direction.UPLOAD ? UiState.UPLOADING : UiState.DOWNLOADING;
            UiState newState = verificationUpdate ? UiState.VERIFYING : transferState;
            // Chỉ hiển thị 100% sau khi dịch vụ xác nhận truyền file thành công.
            double percentage = Math.min(TransferItem.MAX_INCOMPLETE_PERCENTAGE, progress.percentage());
            // Hàng transfer và trạng thái của controller chỉ được sửa trên EDT.
            enqueueOnEdt(() -> {
                TransferItem current = findTransferByRowId(rowId);
                updateTransferItem(current.withProgress(progress.bytesTransferred(), progress.totalBytes(), percentage,
                        bytesPerSecond, newState, verificationUpdate ? "Verifying SHA-256 and confirming completion ..."
                                : "Transferring binary data ..."));
                state = newState;
                statusMessage = current.fileName() + " - " + newState;
                renderSnapshot();
            });
        }
    }

    /**
     * Chạy thao tác mạng hoặc file trên worker để EDT tiếp tục nhận thao tác người dùng.
     * Đưa cả kết quả lẫn lỗi về EDT trước khi cập nhật trạng thái và giao diện.
     */
    private <T> void executeInBackground(Callable<T> operation, Consumer<T> onSuccess, Consumer<Exception> onFailure) {
        worker.execute(() -> {
            try {
                T result = operation.call();
                enqueueOnEdt(() -> onSuccess.accept(result));
            } catch (Exception ex) {
                LOG.log(System.Logger.Level.WARNING, "Desktop operation failed", ex);
                // Truy vấn trạng thái backend trên worker vì có thể chờ khóa của dịch vụ.
                boolean stillConnected = backend.connected();
                enqueueOnEdt(() -> {
                    connected = stillConnected;
                    onFailure.accept(ex);
                });
            }
        });
    }

    private void enqueueOnEdt(Runnable action) {
        // Khi cửa sổ đã đóng, kết quả đang chờ trong hàng đợi không còn cần vẽ.
        SwingUtilities.invokeLater(() -> {
            if (!closed.get()) {
                action.run();
            }
        });
    }

    private void operationFailed(String title, Exception error) {
        finishOperation(UiState.FAILED, ErrorMessages.describe(error));
        view.log(title + ": " + statusMessage);
        view.notice(title, statusMessage, true);
    }

    private boolean canStartOperation() {
        return !closed.get() && !busy;
    }

    private boolean ensureConnected() {
        if (connected) {
            return true;
        }
        view.notice("Not connected", "Connect to a server first.", false);
        return false;
    }

    private void startOperation(UiState newState, String newStatusMessage) {
        busy = true;
        disconnecting = false;
        state = newState;
        statusMessage = newStatusMessage;
        renderSnapshot();
    }

    private void finishOperation(UiState newState, String newStatusMessage) {
        busy = false;
        disconnecting = false;
        state = newState;
        statusMessage = newStatusMessage;
        renderSnapshot();
    }

    private String fileSummary() {
        return files.isEmpty() ? "No files on this server." : files.size() + " completed file(s) available.";
    }

    private TransferItem findTransferByRowId(UUID rowId) {
        for (TransferItem item : transfers) {
            if (item.rowId().equals(rowId)) {
                return item;
            }
        }
        return null;
    }

    private void updateTransferItem(TransferItem updatedItem) {
        for (int row = 0; row < transfers.size(); row++) {
            if (transfers.get(row).rowId().equals(updatedItem.rowId())) {
                transfers.set(row, updatedItem);
                return;
            }
        }
        throw new IllegalStateException("Unknown UI transfer row");
    }

    private void renderSnapshot() {
        view.render(snapshot());
    }

    /** Hoàn tất khi luồng điều khiển đã đóng discovery và kết nối. */
    public CompletableFuture<Void> termination() {
        return shutdownCompletion;
    }

    @Override
    /** Đóng cửa sổ trên EDT và giao việc giải phóng kết nối cho luồng điều khiển. */
    public void close() {
        requireEdt();
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        discoveryCancelled.set(true);
        statusMessage = "Closing connection ...";
        renderSnapshot();
        // Đóng socket để worker thoát I/O. Không ngắt worker giữa lúc dịch vụ đang ghi checkpoint.
        worker.shutdown();
        control.execute(() -> {
            try {
                discovery.close();
                backend.interrupt();
                backend.close();
                shutdownCompletion.complete(null);
            } catch (Exception ex) {
                LOG.log(System.Logger.Level.WARNING, "Desktop shutdown failed", ex);
                shutdownCompletion.completeExceptionally(ex);
            } finally {
                control.shutdown();
            }
        });
        view.closed();
    }

    static void requireOffEdt() {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Service operations must run off the EDT");
        }
    }

    static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("UI state must be accessed on the EDT");
        }
    }
}
