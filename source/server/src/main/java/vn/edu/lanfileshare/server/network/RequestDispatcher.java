package vn.edu.lanfileshare.server.network;
import java.io.IOException;
import java.util.UUID;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.protocol.*;
import vn.edu.lanfileshare.server.storage.FileCatalog;
import vn.edu.lanfileshare.server.transfer.TransferService;
public final class RequestDispatcher {
    private final FileCatalog catalog;
    private final TransferService transfers;
    public RequestDispatcher(FileCatalog catalog, TransferService transfers) { this.catalog = catalog; this.transfers = transfers; }
    public void dispatch(SessionContext session, Frame frame) throws IOException {
        switch (frame.type()) {
            case LIST_FILES -> {
                ListFiles request = decodeList(frame);
                session.send(ControlCodec.encode(MessageType.FILE_LIST, new FileList(1, request.requestId(), catalog.listFiles())));
            }
            case UPLOAD_INIT, DOWNLOAD_INIT, RESUME_QUERY, PROGRESS_ACK, TRANSFER_COMPLETE, FILE_CHUNK -> {
                try { MessageValidator.validate(frame); }
                catch (ControlCodec.UnsupportedVersionException ex) { throw ex; }
                catch (IOException ex) { throw new ProtocolException(ErrorCode.BAD_REQUEST, "Invalid control request", false); }
                transfers.handle(session, frame);
            }
            default -> {
                try { ControlCodec.validate(frame); }
                catch (ControlCodec.UnsupportedVersionException ex) { throw ex; }
                catch (IOException ex) { throw new ProtocolException(ErrorCode.BAD_REQUEST, "Invalid control request", false); }
                error(session, ControlCodec.requestId(frame), ErrorCode.BAD_REQUEST, "Unsupported client request");
            }
        }
    }
    private static ListFiles decodeList(Frame frame) throws IOException {
        try { return ControlCodec.decode(frame, ListFiles.class); }
        catch (ControlCodec.UnsupportedVersionException ex) { throw ex; }
        catch (IOException ex) { throw new ProtocolException(ErrorCode.BAD_REQUEST, "Invalid LIST_FILES", false); }
    }
    public void disconnected(UUID sessionId) { transfers.disconnected(sessionId); }
    public static void error(SessionContext session, UUID requestId, ErrorCode code, String message) throws IOException {
        session.send(ControlCodec.encode(MessageType.ERROR, new ErrorMessage(1, requestId, code, message, null)));
    }
}
