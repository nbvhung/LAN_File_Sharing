package vn.edu.lanfileshare.common.error;

/** Stable wire error codes from docs/protocol.md. */
public enum ErrorCode {
    BAD_REQUEST, UNSUPPORTED_VERSION, INVALID_FRAME, NOT_FOUND, INVALID_NAME,
    FILE_TOO_LARGE, SERVER_BUSY, OFFSET_MISMATCH, TRANSFER_NOT_FOUND,
    TRANSFER_CHANGED, HASH_MISMATCH, IO_ERROR, INTERNAL_ERROR
}
