package vn.edu.lanfileshare.client.ui;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import vn.edu.lanfileshare.client.network.ServerResponseException;
import vn.edu.lanfileshare.client.transfer.TransferInterruptedException;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.protocol.ProtocolException;

/** Chuyển lỗi dịch vụ thành thông báo để người dùng biết bước xử lý tiếp theo. */
public final class ErrorMessages {
    private static final int MAX_DETAIL_LENGTH = 240;

    private ErrorMessages() {
    }

    public static String describe(Throwable error) {
        if (error instanceof TransferInterruptedException) {
            return "Transfer interrupted. Progress was saved. Reconnect to the same server, then select Resume.";
        }
        if (error instanceof ServerResponseException response) {
            return describeServerError(response.code());
        }
        if (error instanceof ProtocolException protocol) {
            return describeServerError(protocol.code());
        }
        if (error instanceof UnknownHostException) {
            return "Cannot resolve server address. Check the IP or hostname.";
        }
        if (error instanceof ConnectException) {
            return "Cannot connect to server. Check IP, TCP port, firewall and whether the server is running.";
        }
        if (error instanceof SocketTimeoutException) {
            return "Connection timeout. The server did not respond in time.";
        }
        if (error instanceof EOFException || error instanceof SocketException) {
            return "Server disconnected. Reconnect before trying again.";
        }
        if (error instanceof AccessDeniedException || error instanceof SecurityException) {
            return "Storage permission denied. Choose a writable folder or readable source file.";
        }
        if (error instanceof FileAlreadyExistsException) {
            return "Destination already exists. Choose a new name; existing files are never overwritten.";
        }
        if (error instanceof NoSuchFileException || error instanceof FileNotFoundException) {
            return "File or folder not found. Check the source, destination and checkpoint.";
        }
        if (error instanceof IllegalArgumentException) {
            return error.getMessage();
        }
        if (error instanceof IOException) {
            return "File or network operation failed. Check file validity, permissions, available space and connection. "
                    + safeDetail(error.getMessage());
        }
        return "Operation failed. Check the application log and try again.";
    }

    private static String safeDetail(String message) {
        // Chi tiết I/O cục bộ chỉ hiển thị dòng đầu để tránh đưa cả log lên hộp thoại.
        if (message == null) {
            return "";
        }
        String firstLine = message.lines().findFirst().orElse("");
        return firstLine.substring(0, Math.min(firstLine.length(), MAX_DETAIL_LENGTH));
    }

    private static String describeServerError(ErrorCode code) {
        return switch (code) {
        case HASH_MISMATCH -> "Hash mismatch. The file was not accepted as complete.";
        case TRANSFER_CHANGED ->
            "Resume failed: source, partial file or transfer metadata changed, or the session is already finished.";
        case TRANSFER_NOT_FOUND ->
            "Resume failed: session not found or expired. The server must remain running; retention is 24 hours.";
        case SERVER_BUSY -> "Server busy. Wait briefly and try again.";
        case NOT_FOUND -> "File not found on the server. Refresh the list.";
        case OFFSET_MISMATCH -> "Transfer offset mismatch. No successful completion was recorded.";
        case INVALID_NAME -> "Invalid file name. Choose a basename without path or control characters.";
        case FILE_TOO_LARGE -> "File exceeds the configured size limit (default 1 GiB).";
        case IO_ERROR -> "Server storage or I/O error. Check permissions and available space.";
        default -> "Server rejected the operation: " + code + ".";
        };
    }
}
