# Phase 3 — TCP File Upload

Nhánh làm việc: `feature/tv2-upload-server`, cập nhật fast-forward từ `main` sau Phase 2. Phần server upload thuộc TV2; client headless đi kèm để chạy và kiểm thử end-to-end. Chưa commit/merge Phase 3: chờ người dùng chạy thử.

## Luồng giao thức v1

```text
Client                                            Server
Kiểm tra source, giới hạn, basename; tính SHA-256
UPLOAD_INIT(requestId, name, size, sha256)       ->
                                               Tạo UUID transferId/fileId, .part và snapshot INITIALIZED
                                             <- TRANSFER_READY(direction=UPLOAD, nextOffset=0)
FILE_CHUNK(transferId, offset, dataLength, data)  ->
                                               Ghi bytes, cập nhật snapshot TRANSFERRING
                                             <- PROGRESS_ACK(nextOffset)
                     ... lặp từng chunk, chỉ một chunk chưa ACK ...
FILE_CHUNK cuối                                ->
                                             <- PROGRESS_ACK(size)
                                               Đóng file handle, kiểm tra size và SHA-256 của .part
                                               Atomic move thành completed/<fileId>
                                               Đăng ký catalog; lưu state COMPLETED
                                             <- TRANSFER_COMPLETE
Client kiểm tra ID/metadata rồi mới trả TransferResult và báo 100%
```

File rỗng: INIT → READY → kiểm tra hash rỗng → COMPLETE, không có FILE_CHUNK/ACK. Không thay frame format, mã type/error, JSON DTO hoặc binary layout. Không dùng HTTP/FTP/RMI và không gửi text ngoài FrameCodec trên socket.

ACK xác nhận bytes đã ghi ở mức ứng dụng và snapshot đã ghi xong; không cam kết fsync chống mất điện. Chunk cuối cũng nhận ACK trước COMPLETE/HASH_MISMATCH. Progress 100% chỉ phát sau khi client nhận và kiểm tra COMPLETE; ACK cuối chưa được xem là thành công.

## Implementation và API

- `UploadService(TcpClient)` hoặc `(TcpClient, maxFileBytes)`: `uploadFile(Path, ProgressListener)` trả `TransferResult`. Mặc định 1 GiB. Giữ một FileChannel cho cả hash và đọc truyền; không dùng `readAllBytes` cho file upload. Kiểm tra size trước và sau hash/truyền. Source phải là regular file đọc được, không phải symlink.
- `ProgressListener.onProgress(UploadProgress)`: bytes được ACK, tổng bytes, phần trăm, elapsedTime, bytesPerSecond. Gọi đồng bộ trên luồng upload; callback không được reenter connection hoặc chờ cùng connection. Thời gian đo từ lúc bắt đầu trao đổi INIT, không gồm lượt hash ban đầu; không sleep để giả lập tiến độ.
- `TransferResult`: transferId, `Messages.FileInfo`, elapsedTime. Chỉ được trả sau COMPLETE hợp lệ.
- `TcpClient.exchange(ConnectionOperation<T>)` khóa toàn bộ giao dịch nhiều frame, chống pipelining/reentry. `ClientConnection.send/receive` dùng FrameCodec, kiểm tra JSON/version/requestId và ERROR. Lỗi I/O/protocol/callback làm đóng kết nối; `listFiles()` vẫn giữ API cũ.
- `UploadHandler implements TransferService`: INIT, FILE_CHUNK, ACK, finalize, quản lý một upload trên mỗi socket và kiểm tra sở hữu transferId. Từ chối download/resume vì chưa thuộc Phase 3. Server cũng có giới hạn dung lượng riêng, không tin giới hạn client.
- `TransferSession`: sở hữu FileChannel `.part`, tiến độ và vòng đời. `TransferState`: INITIALIZED, TRANSFERRING, COMPLETED, INTERRUPTED, FAILED.
- `TransferStateStore`: atomic snapshot, đọc/ghi được đồng bộ qua tập lock hữu hạn theo path để tránh Windows từ chối replace JSON đang mở. `read(UUID)` phục vụ chẩn đoán; không có handler phục hồi hay cấp quyền resume từ snapshot.
- `FileStorageService.partPath(UUID)` và `verifyAndPromote(UUID, FileInfo)`: chỉ dùng UUID do server cấp; kiểm tra size/hash trước khi move vào completed. Sau đó `JsonFileCatalog.registerCompletedFile` xác thực lại bytes trước công bố.
- `FileHashes`: SHA-256 với buffer 64 KiB dùng chung. `common.cli.CliArguments` tái sử dụng cách truyền tham số local Unicode; wrapper server cũ còn tương thích.

## Dữ liệu runtime, lỗi và nền tảng resume

```text
staging/<transferId>.part
staging/<transferId>.json
completed/<fileId>
metadata/<fileId>.json
```

Snapshot staging có schemaVersion, transferId, fileId, requestId, sessionId, direction, name, size, sha256, nextOffset, state và updatedAtEpochMillis; không chứa absolute path. Staging không được FileCatalog quét để công bố.

- Chunk lặp nằm hoàn toàn trước nextOffset: bỏ qua bytes, trả ACK hiện tại; không ghi trùng. Chunk nhảy/chồng lấn/vượt expected size trả OFFSET_MISMATCH và không cập nhật tiến độ.
- TransferId thuộc socket khác trả TRANSFER_NOT_FOUND; chunk chưa có INIT hoặc binary layout hỏng thì đóng kết nối có kiểm soát. Response của upload giữ requestId của INIT.
- Tên không hợp lệ trả INVALID_NAME, metadata JSON sai trả BAD_REQUEST, quá giới hạn server trả FILE_TOO_LARGE. Client cũng kiểm tra trước INIT.
- Hash sai: state FAILED, xóa `.part` hỏng, giữ snapshot chẩn đoán, trả HASH_MISMATCH, không có catalog entry.
- Ngắt kết nối giữa chừng: đóng file handle, giữ `.part` và snapshot INTERRUPTED với offset đã ghi. Không báo hoàn tất hoặc công bố file chưa verify. Không có tự reconnect/resume hoặc restore sau restart.
- Pending data được giữ, không tự xóa sớm trước khoảng lưu 24 giờ của v1. Phase 3 chưa có bộ dọn TTL/quota staging; quản trị theo dõi dung lượng. Phase 5 cần xác thực lại snapshot/bytes, tuổi phiên và trạng thái trước khi resume; không được dùng `read(UUID)` như một cơ chế xác thực.
- Move bytes, catalog JSON và snapshot là các thao tác độc lập; không có transaction/fsync chống mất điện giữa nhiều file. Lỗi ghi catalog có thể để lại bytes đã verify nhưng chưa công bố. Nếu server đã commit rồi mất kết nối trước khi client nhận COMPLETE, client vẫn báo lỗi; không tự rollback file đã verify và không tự retry vì có thể tạo bản sao. Tình huống xác nhận bị mất cần xử lý riêng ở phase tiếp theo.

## Chạy thử bằng CLI

**Dừng server phiên bản cũ bằng Ctrl+C trước khi build/chạy lại** để server thực sự nạp code upload mới. Tại repository root:

```powershell
./build.ps1 compile test package

# Terminal 1
./run-server.ps1 -Port 49000 -StorageDirectory runtime/server

# Terminal 2: truyền nội dung README.md qua TCP
./run-upload.ps1 -LocalFile README.md -ServerHost 127.0.0.1 -Port 49000
./run-client.ps1 -ServerHost 127.0.0.1 -Port 49000
```

Upload CLI in progress thực rồi `UPLOAD_COMPLETE transferId=... fileId=... name=... size=... sha256=...`. Listing sau đó thấy UUID mới; nếu trước đó đã có README.md trong catalog thì có nhiều dòng trùng tên nhưng khác ID.

Đối chiếu bytes/hash tại server bằng fileId vừa nhận:

```powershell
Get-FileHash -Algorithm SHA256 README.md
Get-FileHash -Algorithm SHA256 'runtime/server/completed/<fileId>'
```

Đổi `-LocalFile` sang file binary, file rỗng hoặc file có tên Unicode để thử thêm. Có thể dùng `-MaxFileBytes` trên cả `run-server.ps1` và `run-upload.ps1`; mặc định mỗi bên là 1073741824. Với LAN, client dùng IP LAN máy server. File source cần giữ ổn định trong lúc hash/upload. Không cần chạy run-catalog.ps1 cho file upload.

## Kiểm thử và kết quả

Unit test hash biết trước, quyền sở hữu channel, state và snapshot; integration dùng TcpServer/UploadHandler/TcpClient thật, filesystem thật và port động. Bao phủ file rỗng, text, binary 1 MiB/10 MiB có byte 0x00, Unicode, tên trùng, client source thiếu/directory/quá giới hạn/unsafe name, server file limit, path traversal, offset gap/overlap/overflow, chunk lặp, sai dataLength, sai hash, source thay đổi sau hash, socket reset và staging không xuất hiện trong LIST_FILES. Mỗi upload thành công so sánh SHA-256 và bytes qua Files.mismatch.

Các peer TCP đối kháng riêng kiểm tra client từ chối READY/ACK/COMPLETE sai ID/offset/metadata, timeout và EOF. Không dùng mock thay cho bằng chứng upload end-to-end.

### Kết quả thực thi

- **PASS:** `./build.ps1 compile`, sau đó `./build.ps1 compile test package` toàn bộ reactor parent/common/server/client/experiments. Lần cuối báo BUILD SUCCESS lúc `2026-11-03T23:29:03+07:00`, thời gian Maven 01:21 min.
- **PASS:** 59 test, failures=0, errors=0, skipped=0: common 12, server 20, client 27. XML/TXT bằng chứng tại `source/{common,server,client}/target/surefire-reports/`.
- **PASS:** 15 test trong UploadIntegrationTest, 7 test peer TCP đối kháng, 3 test TransferSession (gồm đọc/ghi snapshot cạnh tranh) và 2 test SHA-256; các test Phase 1/2 vẫn PASS.
- **PASS:** chạy `run-upload.ps1` thực tế từ JAR, sau đó `run-client.ps1` nhận đúng metadata qua TCP. Không dùng công cụ đăng ký local để thay thế upload.
- **PASS:** chạy JVM server `-Xmx32m` và JVM client `-Xmx32m`, truyền binary 10485760 byte có byte 0x00; cả hai không OOM, client xác nhận COMPLETE. Python hashlib kiểm tra độc lập SHA-256 giữa source và bytes trong completed.
- **PASS:** git diff --check. Không đổi docs/protocol.md, FrameCodec, MessageType, ErrorCode, Messages hoặc FileChunkCodec.
- Môi trường: Maven 3.9.11, JDK 23.0.1, compiler release 21. Không thêm dependency. Chưa chạy trên hai máy LAN vật lý hoặc runtime JDK 21 riêng.

Lần test đầu phát hiện Windows từ chối atomic replace snapshot đang có reader mở; đã sửa bằng bounded striped locks dùng chung giữa các TransferStateStore trong JVM, thêm test cạnh tranh và chạy lại toàn bộ thành công. Không dùng kết quả lần fail làm bằng chứng PASS.

Bằng chứng CLI thực tế, server localhost port động 55144:

```text
UPLOAD_COMPLETE transferId=a647f8f9-ccac-492b-acd3-2588409bf292 fileId=aa42e541-0cb6-4576-8cc5-8c0e679aa710 name=hello.txt size=3 sha256=ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
UPLOAD_COMPLETE transferId=4c820ebe-2bb4-4ff1-89dc-f8d693dd411c fileId=25d812b8-94b6-4fd0-992a-0071c4cc85d0 name=binary-10MiB.bin size=10485760 sha256=82c3feed86346b6aa8ef09561563f41d0c08df0daf551cd03d5a199d8905221d
FILE_LIST protocolVersion=1 requestId=8383d566-70a6-401b-b126-e292af52e792 count=2
```

Bằng chứng chi tiết local (không commit runtime): `runtime/phase3-smoke-dd40a50c/evidence.json`, `server.log`, source và completed bytes. Process smoke server đã được dừng sau kiểm tra.

Giới hạn dung lượng 1 GiB mặc định được kiểm tra bằng cấu hình giới hạn nhỏ để tránh tạo file 1 GiB chỉ nhằm thử từ chối. Dữ liệu lớn nhất thực sự upload trong lượt này là 10 MiB; không tuyên bố đã truyền file 1 GiB hoặc số liệu benchmark LAN.

## File tạo mới/chỉnh sửa

Các đường dẫn Java dưới đây tính từ `vn.edu.lanfileshare.client`, `.server` hoặc `.common` tương ứng module.

| Thao tác | Module/thư mục | File |
| --- | --- | --- |
| Tạo | client/main | UploadMain.java |
| Tạo | client/main | network/ClientConnection.java |
| Tạo | client/main | transfer/UploadService.java |
| Tạo | client/main | transfer/ProgressListener.java |
| Tạo | client/main | transfer/UploadProgress.java |
| Tạo | client/main | transfer/TransferResult.java |
| Tạo | server/main | transfer/UploadHandler.java |
| Tạo | server/main | transfer/TransferSession.java |
| Tạo | server/main | transfer/TransferSnapshot.java |
| Tạo | server/main | transfer/TransferState.java |
| Tạo | server/main | transfer/TransferStateStore.java |
| Tạo | common/main | io/FileHashes.java |
| Tạo | common/main | cli/CliArguments.java |
| Tạo | client/test | UploadIntegrationTest.java |
| Tạo | client/test | UploadResponseValidationTest.java |
| Tạo | server/test | transfer/TransferSessionTest.java |
| Tạo | common/test | io/FileHashesTest.java |
| Tạo | repository | run-upload.ps1 |
| Tạo | repository | docs/phase3.md |
| Sửa | client/main | network/TcpClient.java |
| Sửa | server/main | ServerMain.java |
| Sửa | server/main | cli/CliArguments.java |
| Sửa | server/main | network/ClientSession.java |
| Sửa | server/main | network/RequestDispatcher.java |
| Sửa | server/main | storage/FileStorageService.java |
| Sửa | server/main | transfer/TransferService.java |
| Sửa | common/main | protocol/ControlCodec.java |
| Sửa | repository | run-server.ps1 |
| Sửa | repository | README.md |

## Kết luận

**READY cho Phase 3 và sẵn sàng để người dùng chạy thử.** Upload đúng bytes/SHA-256, hoàn tất mới được công bố, progress/ACK tuần tự, lỗi được kiểm soát và staging không lộ trong LIST_FILES. Giới hạn còn lại: chưa download/reconnect/resume/TTL cleanup, chưa fsync/transaction nhiều file, chưa thử LAN vật lý và chưa upload 1 GiB thực tế. Chờ người dùng test xong trước bước commit/merge; hiện giữ thay đổi trên nhánh TV2.
