package vn.edu.lanfileshare.common.protocol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import vn.edu.lanfileshare.common.model.Messages.*;
public final class MessageValidator {
    private MessageValidator() {}
    public static void validate(Frame frame) throws IOException {
        switch (frame.type()) {
            case FILE_CHUNK -> FileChunkCodec.decode(frame.payload());
            case UPLOAD_INIT -> {
                UploadInit r = ControlCodec.decode(frame, UploadInit.class);
                String name = r.name();
                if (name == null || name.isBlank() || name.contains("/") || name.indexOf(92) >= 0 || name.contains("..")
                    || name.codePoints().anyMatch(Character::isISOControl) || name.getBytes(StandardCharsets.UTF_8).length > 255)
                    throw new IOException("Invalid file name");
                metadata(r.size(), r.sha256());
            }
            case ERROR -> ControlCodec.decode(frame, ErrorMessage.class);
            case DOWNLOAD_INIT -> require(ControlCodec.decode(frame, DownloadInit.class).fileId());
            case RESUME_QUERY -> {
                ResumeQuery r = ControlCodec.decode(frame, ResumeQuery.class);
                require(r.transferId()); require(r.fileId()); require(r.direction()); metadata(r.size(), r.sha256());
            }
            case PROGRESS_ACK -> {
                ProgressAck r = ControlCodec.decode(frame, ProgressAck.class);
                require(r.transferId()); if (r.nextOffset() < 0) throw new IOException("Invalid offset");
            }
            case TRANSFER_COMPLETE -> {
                TransferComplete r = ControlCodec.decode(frame, TransferComplete.class);
                require(r.transferId()); require(r.fileId()); metadata(r.size(), r.sha256());
            }
            default -> throw new IOException("Unexpected transfer message");
        }
    }
    private static void require(Object value) throws IOException { if (value == null) throw new IOException("Missing required field"); }
    private static void metadata(long size, String hash) throws IOException {
        if (size < 0 || hash == null || !hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid file metadata");
    }
}
