package vn.edu.lanfileshare.client;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.client.network.*;
import vn.edu.lanfileshare.client.transfer.*;
import vn.edu.lanfileshare.common.error.ErrorCode;
import vn.edu.lanfileshare.common.io.FileHashes;
import vn.edu.lanfileshare.common.model.Messages.*;
import vn.edu.lanfileshare.common.protocol.*;
import vn.edu.lanfileshare.server.network.*;
import vn.edu.lanfileshare.server.storage.*;
import vn.edu.lanfileshare.server.transfer.*;

class ResumableTransferIntegrationTest {
    @TempDir Path directory;
    private FileStorageService storage; private JsonFileCatalog catalog;
    private UploadCheckpointStore checkpoints;
    private static final List<Map<String,Object>> EVIDENCE = Collections.synchronizedList(new ArrayList<>());
    static final class MutableClock extends Clock {
        private final AtomicLong time = new AtomicLong(System.currentTimeMillis());
        void advance(Duration duration) { time.addAndGet(duration.toMillis()); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(time.get()); }
        public long millis() { return time.get(); }
    }
    @BeforeAll static void warmEntropy() { UUID.randomUUID(); }
    @AfterAll static void saveEvidence() throws Exception {
        Files.createDirectories(Path.of("target"));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target/resume-evidence.json").toFile(), EVIDENCE);
    }
    @BeforeEach void setup() throws Exception {
        storage = new FileStorageService(new StorageConfiguration(directory.resolve("storage")));
        catalog = new JsonFileCatalog(storage); checkpoints = new UploadCheckpointStore(directory.resolve("client-state"));
    }
    record Running(TcpServer server, TransferCoordinator transfers, MutableClock clock) implements AutoCloseable {
        public void close() throws IOException { try { server.close(); } finally { transfers.close(); } }
        TcpClient client() throws IOException { TcpClient c = new TcpClient(5000); c.connect("127.0.0.1", server.port()); return c; }
        void detached(UUID id) throws Exception { assertTrue(transfers.sessions().awaitDetached(id, Duration.ofSeconds(5)), "Old socket lease must be released"); }
    }
    private Running start() throws Exception {
        MutableClock clock = new MutableClock(); var transfers = new TransferCoordinator(storage, catalog, 1L << 30, clock);
        TcpServer server = new TcpServer(InetAddress.getLoopbackAddress(), 0, 8, 5000, new RequestDispatcher(catalog, transfers));
        server.start(); return new Running(server, transfers, clock);
    }
    private UploadService uploads(TcpClient client) { return new UploadService(client, 1L << 30, checkpoints); }
    private Path source(int size) throws IOException {
        Path file = directory.resolve("source-" + size + ".bin"); Random random = new Random(19);
        try (OutputStream out = Files.newOutputStream(file)) {
            byte[] block = new byte[65536];
            for (int remaining = size; remaining > 0; remaining -= Math.min(remaining, block.length)) { random.nextBytes(block); block[0]=0; out.write(block,0,Math.min(remaining,block.length)); }
        }
        return file;
    }
    private static void cut(TcpClient client) { try { client.disconnect(); } catch (IOException ex) { throw new UncheckedIOException(ex); } }
    private TransferInterruptedException interruptUpload(TcpClient client, UploadService service, Path source, long offset) {
        AtomicBoolean once = new AtomicBoolean();
        TransferInterruptedException error = assertThrows(TransferInterruptedException.class, () -> service.uploadFile(source, p -> {
            if (p.bytesTransferred() >= offset && p.percentage() < 100 && once.compareAndSet(false,true)) cut(client);
        }));
        assertTrue(once.get()); assertEquals(offset,error.nextOffset()); assertEquals(ClientState.INTERRUPTED,client.state()); return error;
    }
    private void assertSaved(Path source, TransferResult result) throws Exception {
        assertEquals(-1, Files.mismatch(source, storage.completedPath(result.file().fileId())));
        assertEquals(FileHashes.sha256(source), FileHashes.sha256(storage.completedPath(result.file().fileId())));
        assertFalse(Files.exists(storage.partPath(result.transferId())));
        assertEquals(result.file(),catalog.findById(result.file().fileId()).orElseThrow());
    }
    @Test void uploadInterruptedAtThirtyPercentSendsOnlyRemainingBytes() throws Exception {
        Path source=source(10*1024*1024); long boundary=3L*1024*1024;
        try (Running running=start(); TcpClient client=running.client()) {
            UploadService service=uploads(client); TransferInterruptedException failure=interruptUpload(client,service,source,boundary);
            running.detached(failure.transferId()); var previous=running.transfers().sessions().snapshot(failure.transferId());
            assertEquals(boundary,previous.nextOffset()); assertEquals(TransferState.INTERRUPTED,previous.state()); assertTrue(catalog.listFiles().isEmpty());
            long before=running.server().metrics().receivedDataBytes(); assertEquals(boundary,before);
            client.reconnect(); assertEquals(ClientState.CONNECTED,client.state()); AtomicBoolean resuming=new AtomicBoolean();
            ResumeResult<TransferResult> resumed=service.resumeUpload(failure.transferId(),source,p -> resuming.set(resuming.get() || client.state()==ClientState.RESUMING));
            assertEquals(boundary,resumed.resumedFromOffset()); assertEquals(Files.size(source)-boundary,resumed.payloadBytesThisAttempt()); assertTrue(resuming.get());
            assertEquals(ClientState.COMPLETED,client.state()); assertSaved(source,resumed.result());
            long total=running.server().metrics().receivedDataBytes(); assertEquals(Files.size(source),total); assertEquals(total,client.metrics().sentDataBytes());
            var current=running.transfers().sessions().snapshot(failure.transferId()); assertNotEquals(previous.sessionId(),current.sessionId()); assertNotEquals(previous.requestId(),current.requestId());
            Map<String,Object> proof=Map.of("direction","UPLOAD","before",before,"after",total-before,"total",total,"fileSize",Files.size(source),"resumedFrom",resumed.resumedFromOffset());
            EVIDENCE.add(proof); System.out.println("RESUME_BYTES "+proof);
        }
    }
    @Test void downloadInterruptedAtFortyPercentUsesReceiverOffsetAndNoRetransmission() throws Exception {
        Path source=source(10*1024*1024); long boundary=4L*1024*1024; Path target=directory.resolve("download.bin");
        try (Running running=start(); TcpClient client=running.client()) {
            UUID id=uploads(client).uploadFile(source,p -> {}).file().fileId(); DownloadService service=new DownloadService(client); AtomicBoolean once=new AtomicBoolean();
            TransferInterruptedException failure=assertThrows(TransferInterruptedException.class,() -> service.downloadFile(id,target,p -> {
                if (p.bytesTransferred()>=boundary && p.status()==TransferStatus.TRANSFERRING && once.compareAndSet(false,true)) cut(client);
            }));
            assertTrue(once.get()); assertEquals(boundary,failure.nextOffset()); running.detached(failure.transferId());
            assertFalse(Files.exists(target)); var sender=running.transfers().sessions().snapshot(failure.transferId());
            assertEquals(boundary-65536,sender.nextOffset()); // Last client ACK was intentionally lost.
            long before=running.server().metrics().sentDataBytes(); assertEquals(boundary,before);
            ResumeResult<DownloadResult> resumed=service.resumeDownload(failure.transferId(),failure.checkpoint(),p -> {});
            assertEquals(boundary,resumed.resumedFromOffset()); assertEquals(Files.size(source)-boundary,resumed.payloadBytesThisAttempt());
            assertEquals(-1,Files.mismatch(source,target)); assertEquals(FileHashes.sha256(source),FileHashes.sha256(target));
            assertFalse(Files.exists(failure.checkpoint())); assertEquals(ClientState.COMPLETED,client.state()); client.listFiles();
            assertEquals(TransferState.COMPLETED,running.transfers().sessions().snapshot(failure.transferId()).state());
            long total=running.server().metrics().sentDataBytes(); assertEquals(Files.size(source),total);
            Map<String,Object> proof=Map.of("direction","DOWNLOAD","before",before,"after",total-before,"total",total,"fileSize",Files.size(source),"resumedFrom",resumed.resumedFromOffset());
            EVIDENCE.add(proof); System.out.println("RESUME_BYTES "+proof);
        }
    }
    @Test void sameUploadCanBeInterruptedAndResumedMultipleTimes() throws Exception {
        Path source=source(20*65536);
        try (Running running=start(); TcpClient client=running.client()) {
            UploadService service=uploads(client); var first=interruptUpload(client,service,source,6L*65536); running.detached(first.transferId());
            AtomicBoolean twice=new AtomicBoolean();
            var second=assertThrows(TransferInterruptedException.class,() -> service.resumeUpload(first.transferId(),source,p -> {
                if (p.bytesTransferred()>=12L*65536 && p.percentage()<100 && twice.compareAndSet(false,true)) cut(client);
            }));
            assertEquals(first.transferId(),second.transferId()); running.detached(first.transferId());
            var last=service.resumeUpload(first.transferId(),source,p -> {}); assertEquals(12L*65536,last.resumedFromOffset()); assertSaved(source,last.result());
            assertEquals(Files.size(source),running.server().metrics().receivedDataBytes());
        }
    }
    @Test void sameDownloadCanBeInterruptedRepeatedly() throws Exception {
        Path source=source(20*65536),target=directory.resolve("download");
        try (Running running=start(); TcpClient client=running.client()) {
            UUID fileId=uploads(client).uploadFile(source,p -> {}).file().fileId(); DownloadService service=new DownloadService(client);
            var first=assertThrows(TransferInterruptedException.class,() -> service.downloadFile(fileId,target,p -> { if(p.bytesTransferred()==8L*65536 && p.status()==TransferStatus.TRANSFERRING) cut(client); }));
            running.detached(first.transferId());
            var second=assertThrows(TransferInterruptedException.class,() -> service.resumeDownload(first.transferId(),first.checkpoint(),p -> { if(p.bytesTransferred()==14L*65536 && p.status()==TransferStatus.TRANSFERRING) cut(client); }));
            assertEquals(first.transferId(),second.transferId()); running.detached(first.transferId());
            var result=service.resumeDownload(first.transferId(),second.checkpoint(),p -> {});
            assertEquals(14L*65536,result.resumedFromOffset()); assertEquals(-1,Files.mismatch(source,target));
            assertEquals(Files.size(source),running.server().metrics().sentDataBytes());
        }
    }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    final class Peer implements AutoCloseable {
        final Socket socket; UUID request; TransferReady ready;
        Peer(Running running) throws IOException { socket=new Socket("127.0.0.1",running.server().port());socket.setSoTimeout(5000); }
        void send(MessageType type,Object dto) throws IOException { FrameCodec.write(socket.getOutputStream(),ControlCodec.encode(type,dto)); }
        Frame read() throws IOException { return FrameCodec.read(socket.getInputStream()); }
        void init(byte[] expected) throws Exception { request=UUID.randomUUID();send(MessageType.UPLOAD_INIT,new UploadInit(1,request,"source.bin",expected.length,hash(expected)));ready=ControlCodec.decode(read(),TransferReady.class); }
        void query(TransferReady original, Direction direction, UUID fileId, long size, String sha) throws Exception {
            request=UUID.randomUUID();send(MessageType.RESUME_QUERY,new ResumeQuery(1,request,original.transferId(),direction,fileId,size,sha));
        }
        void resume(TransferReady original) throws Exception {
            query(original,original.direction(),original.fileId(),original.size(),original.sha256()); Frame reply=read();assertEquals(MessageType.TRANSFER_READY,reply.type());
            ready=ControlCodec.decode(reply,TransferReady.class);assertEquals(original.transferId(),ready.transferId());assertEquals(request,ready.requestId());
        }
        void chunk(long offset,byte[] data) throws IOException { FrameCodec.write(socket.getOutputStream(),new Frame(MessageType.FILE_CHUNK,FileChunkCodec.encode(new FileChunk(ready.transferId(),offset,data)))); }
        void ack(long expected) throws Exception { ProgressAck ack=ControlCodec.decode(read(),ProgressAck.class);assertEquals(request,ack.requestId());assertEquals(expected,ack.nextOffset()); }
        void error(ErrorCode code) throws Exception { Frame reply=read();assertEquals(MessageType.ERROR,reply.type());ErrorMessage error=ControlCodec.decode(reply,ErrorMessage.class);assertEquals(code,error.code());assertEquals(request,error.requestId()); }
        public void close() throws IOException { socket.close(); }
    }
    private TransferReady partial(Running running) throws Exception {
        try(Peer peer=new Peer(running)) { peer.init(new byte[]{0,1,2,3,4,5});peer.chunk(0,new byte[]{0,1,2});peer.ack(3);return peer.ready; }
    }
    @Test void unknownTransferIsRejected() throws Exception {
        try(Running running=start();Peer peer=new Peer(running)) {
            peer.request=UUID.randomUUID();peer.send(MessageType.RESUME_QUERY,new ResumeQuery(1,peer.request,UUID.randomUUID(),Direction.UPLOAD,UUID.randomUUID(),1,"0".repeat(64)));peer.error(ErrorCode.TRANSFER_NOT_FOUND);
        }
    }
    @Test void metadataMismatchDoesNotPoisonTheValidTransfer() throws Exception {
        try(Running running=start()) {
            TransferReady original=partial(running);running.detached(original.transferId());
            try(Peer peer=new Peer(running)) {
                peer.query(original,Direction.UPLOAD,original.fileId(),7,original.sha256());peer.error(ErrorCode.TRANSFER_CHANGED);
                peer.query(original,Direction.UPLOAD,original.fileId(),6,"0".repeat(64));peer.error(ErrorCode.TRANSFER_CHANGED);
                peer.query(original,Direction.DOWNLOAD,original.fileId(),6,original.sha256());peer.error(ErrorCode.TRANSFER_CHANGED);
                peer.query(original,Direction.UPLOAD,UUID.randomUUID(),6,original.sha256());peer.error(ErrorCode.TRANSFER_CHANGED);
                peer.resume(original);assertEquals(3,peer.ready.nextOffset());
            }
        }
    }
    @Test void duplicateOverlapGapAndOverflowStayCorrectAfterResume() throws Exception {
        try(Running running=start()) {
            TransferReady original=partial(running);running.detached(original.transferId());
            try(Peer peer=new Peer(running)) {
                peer.resume(original);peer.chunk(0,new byte[]{9,9,9});peer.ack(3);
                peer.chunk(2,new byte[]{2,3});peer.error(ErrorCode.OFFSET_MISMATCH);
                peer.chunk(4,new byte[]{4});peer.error(ErrorCode.OFFSET_MISMATCH);
                peer.chunk(3,new byte[]{3,4,5,6});peer.error(ErrorCode.OFFSET_MISMATCH);
                peer.chunk(3,new byte[]{3,4,5});peer.ack(6);var complete=ControlCodec.decode(peer.read(),TransferComplete.class);
                assertEquals(peer.request,complete.requestId());assertArrayEquals(new byte[]{0,1,2,3,4,5},Files.readAllBytes(storage.completedPath(original.fileId())));
            }
        }
    }
    @Test void missingOrTamperedServerPartIsNotResumed() throws Exception {
        try(Running running=start()) {
            for(boolean missing:List.of(true,false)) {
                TransferReady original=partial(running);running.detached(original.transferId());
                if(missing) Files.delete(storage.partPath(original.transferId()));else Files.write(storage.partPath(original.transferId()),new byte[]{9,9,9});
                try(Peer peer=new Peer(running)) {peer.query(original,Direction.UPLOAD,original.fileId(),6,original.sha256());peer.error(ErrorCode.TRANSFER_CHANGED);}
                assertTrue(catalog.listFiles().isEmpty());
            }
        }
    }
    @Test void uncommittedServerTailIsTruncatedAfterPrefixVerification() throws Exception {
        try(Running running=start()) {
            TransferReady original=partial(running);running.detached(original.transferId());
            Files.write(storage.partPath(original.transferId()),new byte[]{8,8},StandardOpenOption.APPEND);
            try(Peer peer=new Peer(running)) {peer.resume(original);assertEquals(3,Files.size(storage.partPath(original.transferId())));peer.chunk(3,new byte[]{3,4,5});peer.ack(6);assertEquals(MessageType.TRANSFER_COMPLETE,peer.read().type());}
        }
    }
    @Test void changedLocalUploadSourceIsRejectedBeforeSendingPayload() throws Exception {
        Path source=source(4*65536);
        try(Running running=start();TcpClient client=running.client()) {
            UploadService service=uploads(client);var failure=interruptUpload(client,service,source,65536);running.detached(failure.transferId());
            long sent=client.metrics().sentDataBytes();Files.write(source,new byte[]{9},StandardOpenOption.APPEND);
            IOException error=assertThrows(IOException.class,() -> service.resumeUpload(failure.transferId(),source,p -> {}));
            assertEquals(ErrorCode.TRANSFER_CHANGED,((vn.edu.lanfileshare.common.protocol.ProtocolException)error).code());assertEquals(sent,client.metrics().sentDataBytes());
        }
    }
    @Test void expiredSessionIsRejectedAndStagingIsRemovedByClock() throws Exception {
        try(Running running=start()) {
            TransferReady original=partial(running);running.detached(original.transferId());
            long activity=running.transfers().sessions().snapshot(original.transferId()).updatedAtEpochMillis();
            running.clock().advance(Duration.ofHours(23));assertEquals(activity,running.transfers().sessions().snapshot(original.transferId()).updatedAtEpochMillis());
            running.clock().advance(Duration.ofHours(1));assertTrue(running.transfers().sessions().purgeExpired()>0);
            assertFalse(Files.exists(storage.partPath(original.transferId())));
            try(Peer peer=new Peer(running)) {peer.query(original,Direction.UPLOAD,original.fileId(),6,original.sha256());peer.error(ErrorCode.TRANSFER_NOT_FOUND);}
        }
    }
    @Test void completedTransferCannotResumeAndExpiryNeverDeletesCompletedBytes() throws Exception {
        Path source=source(65536);
        try(Running running=start();TcpClient client=running.client()) {
            var result=uploads(client).uploadFile(source,p -> {});
            try(Peer peer=new Peer(running)) {peer.request=UUID.randomUUID();peer.send(MessageType.RESUME_QUERY,new ResumeQuery(1,peer.request,result.transferId(),Direction.UPLOAD,result.file().fileId(),result.file().size(),result.file().sha256()));peer.error(ErrorCode.TRANSFER_CHANGED);}
            running.clock().advance(Duration.ofHours(24));running.transfers().sessions().purgeExpired();assertSaved(source,result);
        }
    }
    @Test void simultaneousResumeClaimsHaveExactlyOneWinner() throws Exception {
        try(Running running=start()) {
            var original=partial(running);running.detached(original.transferId());ExecutorService executor=Executors.newFixedThreadPool(2);
            try(Peer one=new Peer(running);Peer two=new Peer(running)) {
                CyclicBarrier barrier=new CyclicBarrier(3);List<Future<Frame>> attempts=new ArrayList<>();
                for(Peer peer:List.of(one,two)) attempts.add(executor.submit(() -> {barrier.await();peer.query(original,Direction.UPLOAD,original.fileId(),6,original.sha256());return peer.read();}));
                barrier.await();List<Frame> replies=new ArrayList<>();for(var attempt:attempts)replies.add(attempt.get(5,TimeUnit.SECONDS));
                assertEquals(1,replies.stream().filter(f -> f.type()==MessageType.TRANSFER_READY).count());
                ErrorMessage failure=ControlCodec.decode(replies.stream().filter(f -> f.type()==MessageType.ERROR).findFirst().orElseThrow(),ErrorMessage.class);
                assertEquals(ErrorCode.SERVER_BUSY,failure.code());
            } finally {executor.shutdownNow();}
        }
    }
    @Test void noRecoveryFromDiskAfterServerRestart() throws Exception {
        TransferReady original;
        try(Running running=start()) {original=partial(running);running.detached(original.transferId());}
        try(Running restarted=start();Peer peer=new Peer(restarted)) {peer.query(original,Direction.UPLOAD,original.fileId(),6,original.sha256());peer.error(ErrorCode.TRANSFER_NOT_FOUND);}
    }
    @Test void activeResumeBusyRetriesAreFinite() throws Exception {
        Path source=Files.write(directory.resolve("source"),new byte[]{0,1,2,3,4,5});
        try(Running running=start();Peer owner=new Peer(running);TcpClient client=running.client()) {
            owner.init(new byte[]{0,1,2,3,4,5});var ready=owner.ready;
            checkpoints.save(new UploadCheckpoint(1,ready.transferId(),new FileInfo(ready.fileId(),ready.name(),ready.size(),ready.sha256()),source.toString(),0,TransferStatus.INTERRUPTED,System.currentTimeMillis()));
            List<Long> delays=new ArrayList<>();ReconnectPolicy policy=new ReconnectPolicy(3,Duration.ofMillis(10),Duration.ofMillis(40),d -> delays.add(d.toMillis()));
            ServerResponseException error=assertThrows(ServerResponseException.class,() -> uploads(client).resumeUpload(ready.transferId(),source,p -> {},policy));
            assertEquals(ErrorCode.SERVER_BUSY,error.code());assertEquals(List.of(10L,20L),delays);assertEquals(0,client.metrics().sentDataBytes());
        }
    }
    @Test void missingOrCorruptedClientDownloadPartDoesNotTriggerDataReplay() throws Exception {
        Path source=source(4*65536);
        try(Running running=start()) {
            for(boolean missing:List.of(true,false)) try(TcpClient client=running.client()) {
                UUID id=uploads(client).uploadFile(source,p -> {}).file().fileId();DownloadService service=new DownloadService(client);
                var failure=assertThrows(TransferInterruptedException.class,() -> service.downloadFile(id,directory.resolve("out-"+missing),p -> {
                    if(p.bytesTransferred()==65536 && p.status()==TransferStatus.TRANSFERRING)cut(client);
                }));
                running.detached(failure.transferId());String name=failure.checkpoint().getFileName().toString();
                Path part=failure.checkpoint().resolveSibling(name.substring(0,name.length()-5));
                if(missing)Files.delete(part);else Files.write(part,new byte[]{9,9,9},StandardOpenOption.WRITE);
                long sent=running.server().metrics().sentDataBytes();
                IOException error=assertThrows(IOException.class,() -> service.resumeDownload(failure.transferId(),failure.checkpoint(),p -> {}));
                assertEquals(ErrorCode.TRANSFER_CHANGED,((vn.edu.lanfileshare.common.protocol.ProtocolException)error).code());
                assertEquals(sent,running.server().metrics().sentDataBytes());
            }
        }
    }
    @Test void changedServerSourceCannotResumeDownload() throws Exception {
        Path source=source(4*65536);
        try(Running running=start();TcpClient client=running.client()) {
            UUID id=uploads(client).uploadFile(source,p -> {}).file().fileId();DownloadService service=new DownloadService(client);
            var failure=assertThrows(TransferInterruptedException.class,() -> service.downloadFile(id,directory.resolve("out"),p -> {
                if(p.bytesTransferred()==65536 && p.status()==TransferStatus.TRANSFERRING)cut(client);
            }));
            running.detached(failure.transferId());Files.writeString(storage.completedPath(id),"changed");
            ServerResponseException error=assertThrows(ServerResponseException.class,() -> service.resumeDownload(failure.transferId(),failure.checkpoint(),p -> {}));
            assertEquals(ErrorCode.TRANSFER_CHANGED,error.code());assertFalse(Files.exists(directory.resolve("out")));
        }
    }
    @Test void downloadResumeWaitsForReceiverAckAndRejectsOutOfRangeOffset() throws Exception {
        Path source=source(2*65536);FileInfo file=storage.importVerified(source,"file");catalog.registerCompletedFile(file);
        try(Running running=start()) {
            TransferReady original;
            try(Peer peer=new Peer(running)) {
                peer.request=UUID.randomUUID();peer.send(MessageType.DOWNLOAD_INIT,new DownloadInit(1,peer.request,file.fileId()));
                peer.ready=ControlCodec.decode(peer.read(),TransferReady.class);original=peer.ready;
                peer.send(MessageType.PROGRESS_ACK,new ProgressAck(1,peer.request,peer.ready.transferId(),0));
                assertEquals(65536,FileChunkCodec.decode(peer.read().payload()).data().length);
            }
            running.detached(original.transferId());
            try(Peer peer=new Peer(running)) {
                peer.resume(original);peer.socket.setSoTimeout(150);assertThrows(SocketTimeoutException.class,() -> peer.socket.getInputStream().read());peer.socket.setSoTimeout(5000);
                peer.send(MessageType.PROGRESS_ACK,new ProgressAck(1,peer.request,original.transferId(),file.size()+1));peer.error(ErrorCode.OFFSET_MISMATCH);
                peer.send(MessageType.PROGRESS_ACK,new ProgressAck(1,peer.request,original.transferId(),65536));
                FileChunk chunk=FileChunkCodec.decode(peer.read().payload());assertEquals(65536,chunk.offset());
                peer.send(MessageType.PROGRESS_ACK,new ProgressAck(1,peer.request,original.transferId(),file.size()));
                peer.send(MessageType.TRANSFER_COMPLETE,new TransferComplete(1,peer.request,original.transferId(),file.fileId(),file.size(),file.sha256()));
                UUID list=UUID.randomUUID();peer.send(MessageType.LIST_FILES,new ListFiles(1,list));assertEquals(list,ControlCodec.decode(peer.read(),FileList.class).requestId());
                peer.query(original,Direction.DOWNLOAD,file.fileId(),file.size(),file.sha256());peer.error(ErrorCode.TRANSFER_CHANGED);
            }
        }
    }
    @Test void expiredDownloadCannotResumeButItsCompletedSourceSurvives() throws Exception {
        Path source=source(4*65536);
        try(Running running=start();TcpClient client=running.client()) {
            UUID id=uploads(client).uploadFile(source,p -> {}).file().fileId();DownloadService service=new DownloadService(client);
            var failure=assertThrows(TransferInterruptedException.class,() -> service.downloadFile(id,directory.resolve("out"),p -> {
                if(p.bytesTransferred()==65536 && p.status()==TransferStatus.TRANSFERRING)cut(client);
            }));
            running.detached(failure.transferId());running.clock().advance(Duration.ofHours(24));running.transfers().sessions().purgeExpired();
            ServerResponseException error=assertThrows(ServerResponseException.class,() -> service.resumeDownload(failure.transferId(),failure.checkpoint(),p -> {}));
            assertEquals(ErrorCode.TRANSFER_NOT_FOUND,error.code());assertEquals(FileHashes.sha256(source),FileHashes.sha256(storage.completedPath(id)));
        }
    }
}
