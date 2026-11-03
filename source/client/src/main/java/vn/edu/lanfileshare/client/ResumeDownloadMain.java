package vn.edu.lanfileshare.client;
import java.nio.file.Path;
import java.util.UUID;
import vn.edu.lanfileshare.client.network.TcpClient;
import vn.edu.lanfileshare.client.transfer.*;
/** ResumeDownloadMain <transferId> <checkpointFile> <host> <port>. */
public final class ResumeDownloadMain {
    private ResumeDownloadMain() {}
    public static void main(String[] args) throws Exception {
        args=vn.edu.lanfileshare.common.cli.CliArguments.decode(args);
        if(args.length!=4)throw new IllegalArgumentException("Usage: ResumeDownloadMain <transferId> <checkpointFile> <host> <port>");
        UUID id=UUID.fromString(args[0]);
        try(TcpClient client=new TcpClient()) {
            client.connect(args[2],Integer.parseInt(args[3]));
            ResumeResult<DownloadResult> resumed=new DownloadService(client).resumeDownload(id,Path.of(args[1]),p -> {});
            System.out.println("RESUME_DOWNLOAD_COMPLETE transferId="+id+" fileId="+resumed.result().file().fileId()
                +" resumedFrom="+resumed.resumedFromOffset()+" payloadBytes="+resumed.payloadBytesThisAttempt()+" sha256="+resumed.result().file().sha256());
        }
    }
}
