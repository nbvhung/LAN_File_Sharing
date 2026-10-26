# server — TV1, TV2, TV4

- `network/` (TV1): accept loop, executor giới hạn, session, dispatcher, timeout.
- `transfer/` (TV2): upload/download, ACK offset, resume, SHA-256, khóa phiên.
- `storage/` (TV2): FileCatalog, metadata, `.part`, đường dẫn an toàn, công bố file hoàn tất.
- `discovery/` (TV4): UDP responder.
- `http/` (TV4): HTTP GET danh sách/download, chỉ đọc catalog hoàn tất; mở rộng tùy tiến độ.

Runtime phải có đường dẫn cấu hình riêng; không tạo dữ liệu trong source tree.

## Phần TV2 đã triển khai

`storage/` và `transfer/` nằm trong package `vn.edu.lanfileshare.server`. Service chạy độc lập với socket/JSON, theo [protocol v1](../../docs/protocol.md). Xem [hướng dẫn bàn giao TV2](../../docs/tv2-handoff.md) để nối dispatcher của TV1 và client của TV3.

- `FileCatalog`: danh sách chỉ đọc, UUID fileId, tên hiển thị an toàn, metadata file hoàn tất được nạp lại khi mở runtime.
- `Sha256`: tính hash theo luồng, không nạp cả file vào RAM.
- `TransferService`: upload `.part`, ACK sau ghi dữ liệu/metadata, hash trước công bố; download từng chunk chờ ACK; resume hai chiều sau ngắt kết nối; khóa riêng từng transfer và gắn với session TCP.
- File mặc định tối đa 1 GiB, tối đa 128 transfer chưa xong, giữ phiên 24 giờ kể từ hoạt động cuối. Constructor cho phép đổi giới hạn và clock. Cần gọi `cleanupExpired()` định kỳ.

Ví dụ khởi tạo ở entry point server của TV1 (một instance dùng chung cho các connection):

```java
FileCatalog catalog = new FileCatalog(Path.of("runtime/server"));
TransferService transfers = new TransferService(catalog);
UUID sessionId = UUID.randomUUID(); // tạo mới cho từng socket, không lấy từ client
try {
    // Dispatcher gọi list/init/chunk/ack/resume/complete theo bảng bàn giao.
} finally {
    transfers.interrupt(sessionId);
}
```

Chỉ một catalog/service được quyền ghi vào một runtime; không chạy hai process dùng chung thư mục. Thư mục runtime phải do server quản lý; client không được sửa trực tiếp dữ liệu.

```text
runtime/server/
  completed/<fileId>.bin
  completed/<fileId>.properties
  partial/<transferId>.part
  partial/<transferId>.properties
```

Tên client chỉ nằm trong metadata. Khi upload xong, file `.part` được chuyển trong cùng filesystem sang `completed`, ghi metadata, rồi mới thêm vào catalog. Nếu filesystem hỗ trợ thì dùng atomic move; fallback là move thường trong cùng filesystem, catalog vẫn chỉ công bố sau khi hoàn tất. Metadata được ghi qua file tạm rồi replace. Không cam kết fsync/chống mất điện. Phiên dang dở không được khôi phục sau restart; file đã công bố vẫn còn trong catalog.

## Kiểm thử TV2

Từ root project:

```powershell
.\build.ps1 test
```

Hoặc từ `source/`, chỉ chạy common + server:

```powershell
mvn "-Dmaven.repo.local=../.m2/repository" -pl server -am test
```

`TransferServiceTest` kiểm tra binary/empty file, hash sai, tên/offset/chunk sai, chunk lặp, resume upload/download, session ownership, metadata rollback, nguồn thay đổi, giới hạn/TTL, 4/8 client đồng thời và tranh chấp cùng transfer. `LargeTransferTest` truyền luồng 1/10/100 MiB, ngắt/resume cả hai chiều và đối chiếu hash. `FileCatalogTest` kiểm tra catalog, metadata hỏng, dọn file mồ côi và symlink (test này được skip nếu Windows không cấp quyền tạo symlink).

Đây là test service/file trên máy local, chưa phải benchmark TCP/LAN. Module hiện chưa có TCP server entry point (TV1), client (TV3) hay discovery (TV4), nên chưa thể chạy demo hai máy.
