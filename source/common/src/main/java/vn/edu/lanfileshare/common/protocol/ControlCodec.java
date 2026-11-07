package vn.edu.lanfileshare.common.protocol;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.UUID;
import java.lang.reflect.RecordComponent;
import com.fasterxml.jackson.core.JsonParser;

public final class ControlCodec {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    private ControlCodec() {
    }

    public static Frame encode(MessageType type, Object message) throws IOException {
        if (type == MessageType.FILE_CHUNK)
            throw new IOException("Use FileChunkCodec");
        byte[] payload = JSON.writeValueAsBytes(message);
        if (payload.length > FrameCodec.MAX_CONTROL_PAYLOAD)
            throw new ControlPayloadTooLargeException();
        return new Frame(type, payload);
    }

    public static <T> T decode(Frame frame, Class<T> type) throws IOException {
        if (frame.type() == MessageType.FILE_CHUNK || frame.payload().length > FrameCodec.MAX_CONTROL_PAYLOAD)
            throw new IOException("Invalid control frame");
        if (dtoType(frame.type()) != type)
            throw new IOException("DTO does not match message type");
        JsonNode node = JSON.readTree(frame.payload());
        if (node == null || !node.isObject() || !node.path("protocolVersion").isIntegralNumber()
                || !node.path("requestId").isTextual())
            throw new IOException("Missing control envelope");
        try {
            String id = node.get("requestId").textValue();
            if (!UUID.fromString(id).toString().equals(id))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid requestId", ex);
        }
        if (!node.get("protocolVersion").canConvertToInt() || node.get("protocolVersion").intValue() != 1)
            throw new UnsupportedVersionException();
        validateFields(node, type);
        if (node.has("size") && node.has("nextOffset")
                && node.get("nextOffset").longValue() > node.get("size").longValue())
            throw new IOException("Offset exceeds file size");
        return JSON.treeToValue(node, type);
    }

    public static void validate(Frame frame) throws IOException {
        decode(frame, dtoType(frame.type()));
    }

    private static Class<?> dtoType(MessageType type) throws IOException {
        return switch (type) {
        case LIST_FILES -> vn.edu.lanfileshare.common.model.Messages.ListFiles.class;
        case FILE_LIST -> vn.edu.lanfileshare.common.model.Messages.FileList.class;
        case UPLOAD_INIT -> vn.edu.lanfileshare.common.model.Messages.UploadInit.class;
        case DOWNLOAD_INIT -> vn.edu.lanfileshare.common.model.Messages.DownloadInit.class;
        case TRANSFER_READY -> vn.edu.lanfileshare.common.model.Messages.TransferReady.class;
        case PROGRESS_ACK -> vn.edu.lanfileshare.common.model.Messages.ProgressAck.class;
        case RESUME_QUERY -> vn.edu.lanfileshare.common.model.Messages.ResumeQuery.class;
        case TRANSFER_COMPLETE -> vn.edu.lanfileshare.common.model.Messages.TransferComplete.class;
        case ERROR -> vn.edu.lanfileshare.common.model.Messages.ErrorMessage.class;
        default -> throw new IOException("Binary message is not JSON");
        };
    }

    private static void validateFields(JsonNode node, Class<?> type) throws IOException {
        if (!type.isRecord())
            throw new IOException("Control DTO must be a record");
        for (RecordComponent field : type.getRecordComponents()) {
            JsonNode value = node.get(field.getName());
            boolean optional = type == vn.edu.lanfileshare.common.model.Messages.ErrorMessage.class
                    && field.getName().equals("transferId");
            if (value == null || value.isNull()) {
                if (optional)
                    continue;
                throw new IOException("Missing required field: " + field.getName());
            }
            Class<?> kind = field.getType();
            if (kind == UUID.class) {
                try {
                    if (!value.isTextual() || !UUID.fromString(value.textValue()).toString().equals(value.textValue()))
                        throw new IllegalArgumentException();
                } catch (IllegalArgumentException ex) {
                    throw new IOException("Invalid UUID: " + field.getName(), ex);
                }
            } else if (kind == long.class || kind == int.class) {
                if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
                    throw new IOException("Invalid integer: " + field.getName());
            } else if (kind == String.class || kind.isEnum()) {
                if (!value.isTextual())
                    throw new IOException("Invalid string: " + field.getName());
                if (field.getName().equals("name")) {
                    String name = value.textValue();
                    if (name.isBlank() || name.contains("/") || name.indexOf(92) >= 0 || name.contains("..")
                            || name.codePoints().anyMatch(Character::isISOControl)
                            || name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255)
                        throw new ProtocolException(vn.edu.lanfileshare.common.error.ErrorCode.INVALID_NAME,
                                "Invalid file name", false);
                }
                if (field.getName().equals("sha256") && !value.textValue().matches("[0-9a-f]{64}"))
                    throw new IOException("Invalid SHA-256");
            } else if (field.getName().equals("files")) {
                if (!value.isArray())
                    throw new IOException("Invalid files array");
                for (JsonNode file : value) {
                    if (!file.isObject())
                        throw new IOException("Invalid file metadata");
                    validateFields(file, vn.edu.lanfileshare.common.model.Messages.FileInfo.class);
                }
            }
        }
    }

    /** Read a usable correlation ID even when the rest of a control request is invalid. */
    public static UUID requestId(Frame frame) throws IOException {
        JsonNode node = JSON.readTree(frame.payload());
        try {
            String id = node.path("requestId").textValue();
            UUID uuid = UUID.fromString(id);
            if (!uuid.toString().equals(id))
                throw new IllegalArgumentException();
            return uuid;
        } catch (RuntimeException ex) {
            throw new IOException("Missing valid requestId", ex);
        }
    }

    /** Local serialization limit; adds no wire message or error code. */
    public static final class ControlPayloadTooLargeException extends IOException {
        public ControlPayloadTooLargeException() {
            super("Control message exceeds 65536 bytes");
        }
    }

    public static final class UnsupportedVersionException extends ProtocolException {
        public UnsupportedVersionException() {
            super(vn.edu.lanfileshare.common.error.ErrorCode.UNSUPPORTED_VERSION, "Unsupported protocol version", true);
        }
    }
}
