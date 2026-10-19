package vn.edu.lanfileshare.common.protocol;

/** Stable numeric identifiers from docs/protocol.md. */
public enum MessageType {
    LIST_FILES(0x01), FILE_LIST(0x02), UPLOAD_INIT(0x03), DOWNLOAD_INIT(0x04),
    TRANSFER_READY(0x05), FILE_CHUNK(0x06), PROGRESS_ACK(0x07),
    RESUME_QUERY(0x08), TRANSFER_COMPLETE(0x09), ERROR(0x0A);

    private final int code;

    MessageType(int code) { this.code = code; }

    public int code() { return code; }

    public static MessageType fromCode(int code) {
        for (MessageType type : values()) {
            if (type.code == code) return type;
        }
        throw new IllegalArgumentException("Unknown message type: " + code);
    }
}
