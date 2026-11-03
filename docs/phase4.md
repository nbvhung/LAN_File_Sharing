# Phase 4 — TCP File Download

Nhánh: `feature/tv3-client-transfer` (TV3), fast-forward từ `main` sau Phase 3. Chưa commit/merge để người dùng chạy thử. Không đổi wire protocol v1, không triển khai Swing hoặc Resume.

## A. Tính năng và luồng giao thức

```text
Client                                         Server
DOWNLOAD_INIT(requestId, fileId)             -> tra FileCatalog, chỉ lấy completed
                                            <- TRANSFER_READY(direction=DOWNLOAD, nextOffset=0)
Tạo .part + checkpoint local
PROGRESS_ACK(nextOffset=0)                   ->
                                            <- FILE_CHUNK(transferId, offset, dataLength, bytes)
Ghi bytes và checkpoint trước ACK
PROGRESS_ACK(nextOffset)                     -> chỉ gửi chunk tiếp khi ACK khớp chính xác
                    ... lặp theo từng chunk tối đa 65536 byte ...
PROGRESS_ACK(size)                          -> chờ xác nhận hoàn tất từ client
Đóng handle .part, kiểm tra size/SHA-256
Công bố file đích bằng thao tác không ghi đè
TRANSFER_COMPLETE                          -> lưu state COMPLETED, đóng handle server
Trả DownloadResult, progress COMPLETED 100%
```

File rỗng vẫn có READY và ACK đầu tiên 0, sau đó verify/publish/COMPLETE; không có FILE_CHUNK. Server không gửi thêm ACK/COMPLETE đáp lại COMPLETE của client vì v1 không định nghĩa response đó. Thao tác tiếp theo trên cùng TCP connection được xử lý sau COMPLETE theo thứ tự stream.

`TransferCoordinator` phối hợp UploadHandler Phase 3 và DownloadHandler mới, thực thi một upload **hoặc** download đang hoạt động trên mỗi socket. Upload, LIST_FILES và download dùng lại được cùng một connection tuần tự. Resume tiếp tục bị từ chối có kiểm soát.

## B. An toàn file và persistence

- API nhận **đường dẫn file đích đầy đủ do caller chọn**; không tự nối `name` do server gửi vào local path. Parent phải tồn tại, là directory và ghi được. Không tự tạo đường dẫn từ server metadata.
- Từ chối file đích đã tồn tại, kể cả directory/symlink; không có chế độ ghi đè ngầm. Kiểm tra sớm và thao tác publish cuối cùng đều bảo vệ chống trùng tên.
- Tạo `.lanfs-<transferId>-<random>.part` trong chính parent đích, dùng buffer hữu hạn và metadata sidecar `.part.json`. Bytes và checkpoint được ghi trước ACK; ACK không cam kết fsync chống mất điện.
- Publish bằng `Files.createLink(destination, part)` rồi xóa tên `.part`: một thao tác tạo tên mới độc quyền, không ghi đè cả khi target xuất hiện giữa chừng. Cách này tránh hành vi implementation-specific của ATOMIC_MOVE khi target đã tồn tại. Chỉ công bố sau khi hash/size khớp.
- Filesystem đích cần hỗ trợ hard link và atomic replacement checkpoint. Windows NTFS đã được kiểm thử; filesystem không hỗ trợ sẽ trả lỗi rõ ràng và không fallback sang ghi đè/copy lộ file chưa hoàn tất.
- Hash sai, sai transferId/offset/layout hoặc lỗi local I/O: không coi là completed; cố gắng gửi ERROR theo code v1, đóng connection và dọn partial không dùng được. File đích cũ không bị đụng tới. Hash sai gửi HASH_MISMATCH, sai offset gửi OFFSET_MISMATCH.
- Timeout/EOF/socket disconnect: giữ partial và checkpoint INTERRUPTED trong parent đích; không tạo destination và không phát 100%. Không tự resume hoặc đọc lại checkpoint trong phase này. Chưa có bộ dọn TTL/quota cho partial giữ lại.
- Nếu mất quyền ghi hoặc cleanup cũng thất bại, exception cleanup được gắn suppressed; không nuốt lỗi. Việc thay thế directory đồng thời bởi tiến trình local độc hại không phải mô hình bảo vệ của phase này; metadata từ server không thể tự điều khiển việc đó.

Checkpoint local gồm schemaVersion, requestId, transferId, FileInfo, targetName do người dùng chọn, nextOffset, status và updatedAtEpochMillis. Server dùng TransferSnapshot/TransferStateStore Phase 3 với direction DOWNLOAD, offset đã được receiver ACK và state. Chỉ có snapshot trên server, không tạo bản `.part` thứ hai cho nguồn download đã completed.

Nguồn server được tìm qua FileCatalog/fileId và mở bằng UUID slot với NOFOLLOW_LINKS. Server kiểm tra size/hash trên chính handle dùng để đọc trước READY. Thiếu record/file hoặc file không completed trả NOT_FOUND; thay đổi phát hiện sau đó trả TRANSFER_CHANGED hoặc I/O error có kiểm soát. Không đưa internal path vào TCP response.

Giới hạn giao dịch: v1 không có ACK cho COMPLETE. Nếu local file đã verified/published nhưng gửi COMPLETE hoặc dọn checkpoint thất bại, caller nhận lỗi và không có progress COMPLETED; file verified đã công bố được giữ, không xóa nhầm dữ liệu. Đây không phải transaction giữa filesystem và TCP. Không cam kết fsync/power-loss recovery.

## C. API cho UI và phase tiếp theo

```java
DownloadService downloads = new DownloadService(tcpClient);
DownloadResult result = downloads.downloadFile(fileId, destinationFile, progress -> {
    // callback chạy trên luồng transfer; UI có thể chuyển cập nhật sang EDT sau này
    long bytes = progress.bytesTransferred();
    double percentage = progress.percentage();
    TransferStatus status = progress.status();
});
```

- `DownloadService(TcpClient)` dùng giới hạn mặc định 1 GiB; constructor `(TcpClient, maxFileBytes)` cho cấu hình riêng. `downloadFile(UUID, Path, ProgressListener<DownloadProgress>)` validate input, nhận/verify/publish và trả DownloadResult.
- `DownloadResult`: transferId, FileInfo đã xác minh, destination canonical và elapsedTime.
- `TransferProgress`: bytesTransferred, totalBytes, percentage, elapsedTime, bytesPerSecond, status. `DownloadProgress` cung cấp INITIALIZED, TRANSFERRING, VERIFYING, COMPLETED, INTERRUPTED, FAILED. 100% chỉ xuất hiện sau verify/publish và gửi COMPLETE.
- `ProgressListener<P extends TransferProgress>` dùng cho cả `UploadProgress` và `DownloadProgress`. UploadService vẫn phát UploadProgress với constructor/accessors cũ; thêm status qua interface. Các lambda/method reference và test upload trong repository vẫn compile/run. Raw listener cũ có thể cần cập nhật generic khi tích hợp source ngoài repository; không có thay đổi DTO/wire.
- `TcpClient.exchange` tiếp tục khóa toàn bộ transfer. Không reenter connection hoặc đợi cùng connection trong callback; không tự động chạy trên EDT.
- `ClientConnection.receive(requestId)` kiểm tra JSON/requestId cho control, kiểm tra binary layout cho FILE_CHUNK. DownloadFile tiếp tục kiểm tra transferId/offset/bounds theo context.
- `TransferCoordinator implements TransferService`, `DownloadHandler`, `DownloadSession`: mở nguồn, chờ ACK đầu, gửi đúng một chunk outstanding, kiểm tra COMPLETE/ERROR, persist state và đóng tài nguyên.
- `FileStorageService.openCompleted(FileInfo)`: trả FileChannel đã xác minh cho caller quản lý. FileCatalog và UploadHandler được dùng lại; chỉ thêm hasActive cho điều phối hai hướng.

Metadata/state là nền tảng cho phase sau; không phải API resume. Phase 5 cần xác thực lại snapshot, source/destination bytes, giới hạn/tuổi phiên và ownership trước khi phục hồi. Không phát triển Swing trong Phase 4.

## D. Lệnh chạy thử

**Dừng server cũ bằng Ctrl+C trước khi build và chạy lại** để nạp code download mới. Tại repository root:

```powershell
./build.ps1 compile test package

# Terminal 1
./run-server.ps1 -Port 49000 -StorageDirectory runtime/server

# Terminal 2: upload rồi lấy fileId từ UPLOAD_COMPLETE hoặc bảng listing
./run-upload.ps1 -LocalFile README.md -ServerHost 127.0.0.1 -Port 49000
./run-client.ps1 -ServerHost 127.0.0.1 -Port 49000
New-Item -ItemType Directory -Force runtime/downloads | Out-Null

# Thay <fileId> bằng UUID thực tế vừa nhận
./run-download.ps1 -FileId '<fileId>' -Destination runtime/downloads/README-copy.md -ServerHost 127.0.0.1 -Port 49000
Get-FileHash -Algorithm SHA256 README.md
Get-FileHash -Algorithm SHA256 runtime/downloads/README-copy.md
```

Client CLI in status/progress thực và DOWNLOAD_COMPLETE. Chạy lại cùng Destination sẽ báo FileAlreadyExistsException; chọn tên đích mới nếu muốn tải thêm bản nữa. Với LAN, thay ServerHost bằng IP LAN của server. Destination tương đối được script resolve từ working directory của caller trước khi chuyển về repository để chạy Java; tham số Unicode đi qua helper Base64 local sẵn có, không phải Base64 trên TCP.

## E. Kiểm thử

End-to-end dùng TcpServer + TransferCoordinator + UploadService + DownloadService thật, filesystem thật, port động. Mỗi file được upload → LIST_FILES → download sang thư mục khác → so sánh Files.mismatch và SHA-256. Sau đó kiểm tra LIST_FILES và upload tiếp trên cùng connection.

Bao phủ file rỗng, text, binary 1 MiB/10 MiB có 0x00, Unicode, NOT_FOUND, bytes chưa đăng ký catalog/file thiếu, destination tồn tại/xuất hiện giữa chừng, hash sai, ACK sai/thiếu, sai ownership, COMPLETE quá sớm, hai transfer cạnh tranh và mất quyền ghi thật qua ACL Windows (phục hồi ACL trong finally).

Các peer TCP đối kháng riêng kiểm tra wrong READY direction/fileId/offset, tên path traversal, FILE_CHUNK sai ID/offset/dataLength, early COMPLETE, EOF, timeout hữu hạn, checkpoint trước ACK, partial INTERRUPTED và duplicate chunk không ghi trùng. Unit test kiểm tra publish độc quyền, duplicate bytes và server DownloadSession/state/file handle. Không dùng mock thay cho luồng end-to-end.

### Kết quả thực thi

- **PASS:** `./build.ps1 compile test package` toàn bộ parent/common/server/client/experiments, BUILD SUCCESS lúc `2026-11-03T23:59:03+07:00`, thời gian Maven 01:05 min.
- **PASS:** 91 test, failures=0, errors=0, skipped=0: common 12, server 23, client 56. Các test Phase 1/2/3 vẫn chạy cùng reactor. Bằng chứng XML/TXT tại `source/{common,server,client}/target/surefire-reports/`.
- **PASS:** 14 DownloadIntegrationTest, 12 DownloadResponseValidationTest, 3 DownloadFileTest, 3 DownloadSessionTest. Test mất quyền ghi thực sự đặt ACL DENY trên directory Windows rồi khôi phục; không mock quyền ghi và không bỏ qua test này.
- **PASS:** CLI từ JAR thực tế upload hello.txt bằng run-upload.ps1, upload binary 10 MiB, gọi run-client.ps1, download binary bằng JVM trực tiếp, download hello.txt bằng run-download.ps1. Tất cả thành công.
- **PASS:** JVM server và các JVM client trực tiếp dùng `-Xmx32m`; truyền binary 10485760 byte có 0x00, không OOM. Python so sánh byte-for-byte và hashlib giữa source, completed server và file nhận; cả ba SHA-256 bằng nhau.
- **PASS:** chạy lại run-download.ps1 vào cùng destination trả exit code 1 với FileAlreadyExistsException, hash file đích cũ giữ nguyên. Các lệnh thành công trả exit code 0.
- **PASS:** git diff --check; không thay docs/protocol.md, FrameCodec, MessageType, ErrorCode, Messages hoặc FileChunkCodec. Common chỉ thêm khả năng validate ERROR đã tồn tại để download server tiếp nhận lỗi từ receiver.
- Môi trường: Maven 3.9.11, Oracle JDK 23.0.1, compiler release 21; không dependency mới. Chưa thử LAN hai máy vật lý, runtime JDK 21 riêng hoặc file 1 GiB thực tế.

Lần test đầu còn lỗi encoding trong fixture tên Unicode do shell đổi ký tự thành `?`; đã ghi lại source test bằng UTF-8 và chạy toàn bộ lại thành công. Lỗi fixture không được tính là bằng chứng PASS. Console codepage của môi trường vẫn có thể hiển thị Unicode trong dòng destination thành `?`; tham số local được chuyển UTF-8 và **đường dẫn thực tế/file bytes đã được xác nhận độc lập**. Không có lỗi đường dẫn trên filesystem trong demo.

Bằng chứng rút gọn từ demo localhost port động 56837:

```text
UPLOAD_COMPLETE fileId=18a5a0b0-870b-4297-aa94-6f15ae4b0075 name=hello.txt size=3 sha256=ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
UPLOAD_COMPLETE fileId=811e0d68-1b1c-4772-a3d9-6fddb71fb271 name=binary-10MiB.bin size=10485760 sha256=a41d8a13d91cae00748deedb2427f43e2d0a8ed7142ce5a69b6fa1443f0c775b
FILE_LIST protocolVersion=1 requestId=adfd9f7a-ca99-4f4e-8828-a7cdb7a98d76 count=2
DOWNLOAD_COMPLETE fileId=811e0d68-1b1c-4772-a3d9-6fddb71fb271 size=10485760 sha256=a41d8a13d91cae00748deedb2427f43e2d0a8ed7142ce5a69b6fa1443f0c775b
DOWNLOAD_COMPLETE fileId=18a5a0b0-870b-4297-aa94-6f15ae4b0075 size=3 sha256=ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
PASS: uploaded/listed/downloaded 10 MiB; byte-for-byte and SHA-256 match; collision rejected; no OOM.
```

Dòng chứng minh được rút gọn các trường transferId/destination để dễ đọc. Dữ liệu gốc và evidence local không commit nằm trong `runtime/phase4-smoke-75785311/` gồm source, storage, downloads, server.log và evidence.json. Process smoke server đã được dừng sau kiểm tra; không dừng server đang chạy của người dùng.

## F. File tạo mới/chỉnh sửa

Đường dẫn Java tính từ package `vn.edu.lanfileshare.client`, `.server` hoặc `.common` tương ứng module.

| Thao tác | Module/thư mục | File |
| --- | --- | --- |
| Tạo | client/main | DownloadMain.java |
| Tạo | client/main | transfer/DownloadService.java |
| Tạo | client/main | transfer/DownloadFile.java |
| Tạo | client/main | transfer/DownloadResult.java |
| Tạo | client/main | transfer/DownloadProgress.java |
| Tạo | client/main | transfer/TransferProgress.java |
| Tạo | client/main | transfer/TransferStatus.java |
| Tạo | server/main | transfer/DownloadHandler.java |
| Tạo | server/main | transfer/DownloadSession.java |
| Tạo | server/main | transfer/TransferCoordinator.java |
| Tạo | client/test | DownloadIntegrationTest.java |
| Tạo | client/test | DownloadResponseValidationTest.java |
| Tạo | client/test | transfer/DownloadFileTest.java |
| Tạo | server/test | transfer/DownloadSessionTest.java |
| Tạo | repository | run-download.ps1 |
| Tạo | repository | docs/phase4.md |
| Sửa | client/main | network/ClientConnection.java |
| Sửa | client/main | transfer/ProgressListener.java |
| Sửa | client/main | transfer/UploadProgress.java |
| Sửa | client/main | transfer/UploadService.java |
| Sửa | common/main | protocol/MessageValidator.java |
| Sửa | server/main | ServerMain.java |
| Sửa | server/main | network/RequestDispatcher.java |
| Sửa | server/main | storage/FileStorageService.java |
| Sửa | server/main | transfer/UploadHandler.java |
| Sửa | repository | README.md |

## Kết luận

**READY cho Phase 4**, dựa trên Maven toàn reactor và CLI TCP end-to-end thực tế. API/headless progress sẵn sàng cho UI sau này. Giới hạn còn lại đã nêu: cần filesystem publish phù hợp, chưa Resume/TTL cleanup/Swing, không transaction filesystem–TCP hoặc fsync, chưa thử LAN vật lý/1 GiB. Code giữ trên nhánh TV3, chờ người dùng chạy thử trước commit/merge.
