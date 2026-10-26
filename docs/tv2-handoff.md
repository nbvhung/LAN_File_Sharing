# Bàn giao TV2 — Server File & Resume

Phạm vi: `source/server/src/main/java/vn/edu/lanfileshare/server/storage` và `transfer`, cùng test tương ứng. Không thay wire format/common của TV1. Vì common hiện chưa có request DTO/dispatcher, các record ở `TransferService` là API nội bộ server để TV1 ánh xạ từ payload v1; không gửi trực tiếp wrapper `StartResult`/`UploadProgress` ra wire.

## API cho dispatcher TV1

Import `vn.edu.lanfileshare.server.transfer.TransferService` và các nested record, `Direction`, `TransferException`; catalog nằm trong `vn.edu.lanfileshare.server.storage`.

| Request / sự kiện | Lời gọi | Frame trả về |
| --- | --- | --- |
| `LIST_FILES` | `listFiles()` | `FILE_LIST {files:[...]}` |
| `UPLOAD_INIT name,size,sha256` | `initUpload(sessionId, name, size, sha256)` | `TRANSFER_READY` từ `result.ready()`, rồi `TRANSFER_COMPLETE` nếu `result.completion()` có giá trị (file rỗng) |
| `DOWNLOAD_INIT fileId` | `initDownload(sessionId, fileId)` | `TRANSFER_READY`; chờ ACK đầu, chưa gửi chunk |
| `FILE_CHUNK` phía upload | `acceptUploadChunk(sessionId, new FileChunk(transferId, offset, data))` | `PROGRESS_ACK` từ `result.ack()`, rồi `TRANSFER_COMPLETE` nếu có |
| `PROGRESS_ACK` phía download | `acknowledgeDownload(sessionId, transferId, nextOffset)` | `FILE_CHUNK` nếu Optional có giá trị; rỗng nghĩa là chờ client complete |
| `RESUME_QUERY` | `resume(sessionId, new ResumeRequest(transferId, direction, fileId, size, sha256))` | `TRANSFER_READY`, rồi `TRANSFER_COMPLETE` nếu upload đã đủ byte và finalize thành công |
| `TRANSFER_COMPLETE` phía download | `completeDownload(sessionId, new Completion(transferId, fileId, size, sha256))` | Không echo complete |
| `ERROR` từ client cho một transfer | `abort(sessionId, transferId)` | Không echo error; không xóa file nguồn download |
| EOF, timeout, đóng socket, lỗi transport | `interrupt(sessionId)` trong `finally` | Giải phóng quyền sở hữu, giữ offset và `.part` để resume |
| Timer bảo trì, ví dụ mỗi phút | `cleanupExpired()` | Số phiên đã hết hạn bị loại; dọn artifact cũ không được theo dõi |

Mỗi socket có UUID session **do server tự tạo**, dùng cùng một `TransferService` chung. Gọi service tuần tự trên mỗi socket; các socket khác nhau chạy song song. Không gửi sessionId trên wire. Một transfer chỉ thuộc một socket tại một thời điểm; socket khác nhận `SERVER_BUSY` cho đến khi phiên cũ được `interrupt`. `interrupt` của socket cũ không được phép ảnh hưởng transfer đã gắn với socket mới.

Dispatcher chịu trách nhiệm framing, JSON schema, trường bắt buộc, UUID parsing, `protocolVersion`, `requestId`, giới hạn control payload và encode/decode binary `FILE_CHUNK`. Record `FileChunk` chứa dữ liệu thuần, không phải payload JSON. Service kiểm tra thêm độ dài `1..65536`, offset không âm và không vượt size (không tràn long), nhưng không thay thế việc kiểm tra `dataLength`/wire bytes của TV1.

Mọi JSON response phải được dispatcher thêm `protocolVersion:1`, `requestId` theo protocol. Lưu requestId mới nhất của INIT/RESUME cho các frame cùng transfer; không dùng UUID session làm requestId. `Ready.direction` serialize bằng `UPLOAD`/`DOWNLOAD`, các UUID thành string.

Khi bắt `TransferException`, đưa `ex.code().name()` và `ex.getMessage()` vào `ERROR`, kèm transferId nếu có. Chỉ log `getCause()` phía server. Với `INVALID_FRAME`/`IO_ERROR`, đóng socket và gọi `interrupt`; các lỗi nghiệp vụ như `OFFSET_MISMATCH` giữ socket nếu parser còn an toàn. Lỗi size/hash request sai trả `BAD_REQUEST`; không lộ đường dẫn/stack trace trong message.

## Hợp đồng offset cho TV3

Upload:

1. Tính size/SHA-256 trước INIT; server cấp transferId/fileId.
2. Gửi đúng một chunk ở `nextOffset`; chỉ gửi chunk tiếp sau ACK. ACK là byte đầu tiên **chưa ghi**.
3. Chunk lặp nằm hoàn toàn trước nextOffset chỉ nhận lại ACK, không ghi lần nữa. Chunk chồng lấn hoặc nhảy offset trả `OFFSET_MISMATCH`.
4. Chunk cuối được kiểm tra hash trước khi trả kết quả hoàn tất. Hash sai xóa `.part`, không list file. File rỗng nhận READY rồi COMPLETE ngay, không gửi chunk.
5. Khi ngắt: kết nối mới gửi RESUME với đủ metadata từ READY. Dùng nextOffset server trả về, không dùng offset gửi cuối phía client. Nguồn client phải còn cùng size/hash.
6. Nếu server đã công bố file nhưng frame COMPLETE bị mất thì transfer đã kết thúc, RESUME trả `TRANSFER_NOT_FOUND`. Có thể LIST để kiểm tra fileId đã biết; không tự upload lại mà bỏ qua khả năng file đã tồn tại.

Download:

1. Sau READY, client gửi ACK `0`; server mới gửi chunk đầu. Với file rỗng, client kiểm tra hash rồi COMPLETE, không có chunk.
2. Ghi `.part` và metadata client trước khi ACK. ACK phải đúng cuối chunk đang chờ; ACK lặp ở đầu chunk đó khiến server gửi lại cùng chunk.
3. Kết nối mới RESUME với metadata đã giữ, rồi ACK bằng kích thước `.part` đã lưu ở client. Offset client có thể lớn hơn offset server đã nhận ACK (mất ACK), nhỏ hơn (client phục hồi phần đã lưu), hoặc bằng size. Chỉ ACK đầu sau RESUME có quyền chọn lại offset `0..size`.
4. READY resume chứa offset ACK gần nhất server đã lưu, nhưng client quyết định offset `.part` của download qua ACK đầu. Server không gửi chunk tự động sau READY.
5. Cuối file, client kiểm tra hash/đổi tên và gửi COMPLETE. Có thể gửi ACK cuối rồi COMPLETE hoặc dùng COMPLETE thay ACK cuối. Chỉ COMPLETE sau khi đã nhận đủ byte; server kiểm tra metadata và file nguồn trước khi giải phóng transfer.

## Trạng thái, lưu trữ và giới hạn

`INIT → READY → TRANSFERRING → COMPLETED | INTERRUPTED | FAILED`. Resume đưa INTERRUPTED về READY. Hoạt động hợp lệ cập nhật lastActivity; interrupt cũng bắt đầu lại thời hạn giữ phiên. Trạng thái hoàn tất/thất bại được xóa khỏi bộ nhớ để không chiếm slot và không resume lại. Lỗi I/O giữ phiên INTERRUPTED để có thể thử lại nếu storage được sửa; khi ghi metadata thất bại, byte mới được truncate về offset trước đó trước khi báo lỗi.

Mỗi transfer có khóa riêng; catalog dùng map concurrent và snapshot immutable. Mặc định file ≤1 GiB, tối đa 128 transfer chưa xong (bao gồm interrupted), TTL 24 giờ. Constructor đầy đủ cho phép đổi giới hạn/clock. Không có quota tổng dung lượng ổ đĩa; lỗi hết dung lượng trả `IO_ERROR`. Dữ liệu theo chunk, mỗi download chỉ giữ tối đa một chunk chờ ACK trong RAM. Hash được tính theo luồng; xác minh nguồn download ở init/resume/complete có chi phí đọc toàn bộ file.

Catalog chỉ lấy file đã được công bố qua service; file rời, `.part` hoặc upload sai hash không được list. FileId và transferId do server sinh. Tên hiển thị được kiểm tra basename, tối đa 255 byte UTF-8; không dùng tên client ghép đường dẫn, cho phép trùng tên với ID riêng. Không theo symbolic link cho data file. Runtime phải do server quản lý và chỉ một process ghi vào đó.

Metadata active lưu direction/ID/tên/size/hash/nextOffset/state/lastActivity trước ACK. Catalog completed được nạp lại sau restart. Active transfer không được nạp lại sau restart; artifact cũ chỉ được dọn sau TTL, fileId completed vẫn ổn định. Không cam kết resume sau restart hoặc chống mất điện. Chuyển file dùng atomic move nếu được hỗ trợ; khi fallback, catalog vẫn chỉ công bố sau khi content/metadata hoàn tất.

## Kiểm tra có thể lặp lại

Từ root: `.\build.ps1 test`. Báo cáo JUnit trong `source/server/target/surefire-reports/` (không commit dữ liệu runtime/target).

- Test file nhị phân và rỗng; tên trùng/path traversal/độ dài UTF-8; chunk lặp, chồng lấn, nhảy offset, quá lớn và overflow.
- Test hash mismatch không công bố, metadata được lưu trước ACK, rollback khi metadata ghi lỗi, retry finalize qua resume khi offset đã bằng size.
- Test ngắt/resume upload và download kể cả mất ACK; kiểm tra toàn bộ metadata; quyền sở hữu phiên; nguồn thay đổi cùng size; nguồn client/server giữ nguyên hash theo hợp đồng.
- Test TTL/giới hạn bằng clock kiểm soát; catalog sau restart; orphan cleanup; từ chối symlink (có thể skip khi OS không cho tạo link).
- Test 4 và 8 thread đóng vai client, upload/download file riêng; 8 thread gửi lặp vào cùng transfer.
- Test luồng file 1/10/100 MiB có resume hai chiều và so sánh hash, không nạp toàn bộ file vào bộ nhớ.

Các test này xác minh phần TV2 trong một JVM. LIST/upload/download qua socket thật, malformed wire payload, timeout TCP, LAN hai máy, GUI và CSV benchmark còn cần phần TV1/TV3/TV4. Không dùng thời gian chạy JUnit làm số liệu tốc độ mạng.

Kết quả xác minh local ngày 26/10/2026: toàn bộ Maven reactor `BUILD SUCCESS`, 43 test được phát hiện (3 common + 40 server), 42 test đạt, 0 failure/error, 1 skip do Windows không cấp quyền tạo symlink. Môi trường thực chạy: Windows 11, JDK 25.0.1, Maven 3.8.8; compiler target Java 21. Lệnh chạy từ `source/`: `mvn -o -ntp "-Dmaven.repo.local=../.m2/repository" test`, sau khi tải dependency. Trong sandbox lần chạy này, `JAVA_TOOL_OPTIONS` đặt `java.io.tmpdir` vào `source/server/target/test-tmp` vì thư mục Temp hệ thống bị chặn thao tác `toRealPath`; không thay cấu hình build của project.
