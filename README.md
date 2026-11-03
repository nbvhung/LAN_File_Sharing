# Chia sẻ file trong mạng LAN

## Phase 5: Resume upload/download

Đã hỗ trợ reconnect và resume TCP v1 hai chiều, giữ transferId/fileId, xác minh offset và SHA-256; session server giữ 24 giờ trong cùng tiến trình. Nhánh triển khai: `feature/tv2-upload-server` (TV2 phụ trách hash/session/resume, phối hợp API client TV3).

```powershell
.\build.ps1 compile test package
# Terminal 1
.\run-server.ps1
# Terminal 2: dùng file >= 1 MiB và đích chưa tồn tại
New-Item -ItemType Directory -Force runtime/downloads | Out-Null
.\run-resume-demo.ps1 -LocalFile 'D:\data\sample.bin' -Destination runtime/downloads/resumed.bin
```

Demo tự ngắt socket ở khoảng 30% upload/40% download rồi tiếp tục phần thiếu. CLI riêng: `run-resume-upload.ps1 -TransferId <uuid>` và `run-resume-download.ps1 -TransferId <uuid> -CheckpointFile <path>` cho phiên đang gián đoạn. Không khôi phục session sau server restart; không resume transfer đã completed. Giữ chính sách không ghi đè file đích.

Đã chạy compile/test/package: **118 test PASS**. Smoke file 100 MiB với heap 32 MiB mỗi tiến trình thành công, upload 30 + 70 MiB và download 40 + 60 MiB, bytes/hash khớp. Xem [báo cáo Phase 5](docs/phase5.md) để biết API, state machine, metadata, số đo, giới hạn và cách chạy CLI.

Project bài tập lớn môn Lập trình mạng. Yêu cầu và phân công lấy từ `../De_tai_Chia_se_file_LAN_Java_Swing.docx`.

## Phạm vi

Server TCP lưu file tập trung; client Java Swing xem danh sách, upload, download. UDP discovery hỗ trợ tìm server; nhập IP trực tiếp luôn khả dụng. File truyền theo khối, có SHA-256, ACK offset và resume khi mất kết nối. HTTP GET danh sách/download là phần mở rộng sau khi lõi ổn định.

## Cấu trúc

| Đường dẫn | Phụ trách | Nội dung |
| --- | --- | --- |
| `source/common` | TV1, phối hợp TV2/TV3 | Frame codec, message, lỗi, hợp đồng giao thức |
| `source/server/network` | TV1 | TCP accept loop, phiên và dispatcher |
| `source/server/transfer`, `storage` | TV2 | Truyền file, catalog, metadata, `.part`, hash/resume |
| `source/client/network`, `transfer`, `ui` | TV3 | TCP client, tiến độ, reconnect, Swing |
| `source/server/discovery`, `http`; `source/client/discovery`; `source/experiments` | TV4 | UDP, HTTP GET, đo tải/CSV |
| `statics` | Cả nhóm | Sơ đồ, ảnh demo, kết quả thực nghiệm |

Mỗi module có `pom.xml`, `src/main/java`, `src/test/java`. File chạy thực tế nằm trong thư mục runtime cấu hình được, không lưu trong source.

## Build

Yêu cầu JDK 21+ và Maven 3.9+. Từ thư mục project, trong PowerShell chạy `./build.ps1 validate`, `./build.ps1 test` hoặc `./build.ps1 package`. Script đặt Maven repository tại `.m2/repository` trong project; thư mục này đã được bỏ qua bởi Git. Có thể chạy trực tiếp từ `source/` bằng `mvn "-Dmaven.repo.local=../.m2/repository" validate`. Đã có LIST_FILES, upload/download và resume TCP; Swing chưa triển khai.

Lần chạy `test/package` đầu tiên cần truy cập Maven Central để tải plugin và thư viện. Nếu mạng hiện tại chặn Maven Central, chạy trên máy có Internet hoặc cấu hình mirror Maven được phép sử dụng; sau khi cache đủ dependency có thể build offline.

## Việc cần chốt trước khi code

Xem [giao thức v1](docs/protocol.md) và [review đề tài](docs/review.md). Mã type, framing, ACK/resume, tên trùng và giới hạn mặc định đã được chốt trong giao thức. Khi cần đổi hợp đồng, cập nhật tài liệu và `common` cùng một pull request.

## Tiến độ

1. Tuần 1: chốt giao thức, codec và TCP skeleton.
2. Tuần 2: list/upload/download cơ bản và Swing kết nối.
3. Tuần 3: nhiều client, SHA-256, tiến độ, log và lỗi.
4. Tuần 4: ACK, reconnect, resume.
5. Tuần 5: discovery, giao diện hoàn chỉnh, HTTP nếu còn thời gian; đo tải.
6. Tuần 6: kiểm thử LAN, tài liệu và demo.

## Nghiệm thu dự kiến

File 1/10/100 MB; 1/4/8 client; hash khớp; ngắt rồi resume; IP sai, timeout, file không tồn tại, frame lỗi; Swing không treo. Ghi số đo thực tế trong `statics/results/`.

## Chạy TCP server (TV1)

```powershell
.\build.ps1 package
.\run-server.ps1
# Hoặc chỉ lắng nghe trên máy local:
.\run-server.ps1 -Port 49000 -BindAddress 127.0.0.1
```

Dừng bằng Ctrl+C. Server đọc catalog thực tế từ thư mục runtime; chỉ trả danh sách rỗng khi chưa có file hợp lệ. Hiện hỗ trợ upload và download TCP; resume hai chiều đã triển khai (xem Phase 5). Test `TcpServerTest` vẫn kiểm tra nền tảng TCP với catalog rỗng và nhiều kết nối.

Hợp đồng triển khai cho TV2/TV3: [docs/tv1-api.md](docs/tv1-api.md).


## Phase 1: TCP client kh?ng giao di?n

Build to?n b? module: `./build.ps1 compile test package`.
M? hai c?a s? PowerShell t?i th? m?c repository:

```powershell
# C?a s? 1; m?c ??nh bind 0.0.0.0:49000, d?ng b?ng Ctrl+C
./run-server.ps1
# C?a s? 2
./run-client.ps1 -ServerHost 127.0.0.1 -Port 49000
```

Client in `FILE_LIST protocolVersion=1 requestId=<UUID> files=[]` r?i ng?t k?t n?i.
C? th? ch?y client nhi?u l?n; server ti?p t?c ph?c v?. ?? th? LAN, d?ng IP LAN
c?a m?y server trong `-ServerHost`, c?ng port v?i `run-server.ps1 -Port ...`.
Phase 1 ch?a tri?n khai truy?n file, resume, UDP runtime ho?c Swing.
Chi ti?t API, ki?m th? v? gi?i h?n: [b?o c?o Phase 1](docs/phase1.md).

## Phase 2: Catalog và danh sách file thực tế (TV2)

Server mặc định lưu tại `runtime/server`, gồm `completed/<UUID>`, `metadata/<UUID>.json` và `staging/`. Chỉ file có metadata hợp lệ, đúng size và SHA-256 mới được công bố. Catalog đọc lại được sau restart. File thiếu/sai hash, metadata hỏng, `.part`, `.tmp` và bytes chưa đăng ký không xuất hiện trong danh sách.

```powershell
./build.ps1 compile test package
# Đăng ký file local trước khi chạy server; chưa có upload qua mạng
./run-catalog.ps1 -LocalFile README.md -DisplayName README.md -StorageDirectory runtime/demo/storage
# Terminal 1
./run-server.ps1 -Port 49000 -StorageDirectory runtime/demo/storage
# Terminal 2
./run-client.ps1 -ServerHost 127.0.0.1 -Port 49000
```

CLI client hiển thị fileId, name, size và sha256. Hai lần đăng ký cùng tên tạo hai UUID khác nhau. StorageDirectory tương đối tính từ repository root khi dùng script; không đặt runtime trong `source/`. Công cụ đăng ký local chỉ dành cho quản trị, chạy khi server đã dừng.

**Giới hạn v1:** FILE_LIST không có pagination. Nếu toàn bộ JSON vượt 65536 byte, server trả `ERROR SERVER_BUSY` với requestId gốc, không cắt JSON hoặc trả thiếu file. Listing tính lại SHA-256 nên có chi phí theo tổng dung lượng file; dữ liệu lớn có thể chạm timeout. Không sửa trực tiếp runtime trong lúc server đang chạy.

Xem [thiết kế, API Phase 3/4 và kiểm thử Phase 2](docs/phase2.md).

## Phase 3: Upload file qua TCP (TV2)

Dừng server cũ bằng Ctrl+C trước khi build và chạy lại. Mở hai terminal tại repository root:

```powershell
./build.ps1 compile test package
# Terminal 1
./run-server.ps1 -Port 49000 -StorageDirectory runtime/server
# Terminal 2
./run-upload.ps1 -LocalFile README.md -ServerHost 127.0.0.1 -Port 49000
./run-client.ps1 -ServerHost 127.0.0.1 -Port 49000
```

Lệnh upload truyền **nội dung file**, không chỉ metadata. Client hash source trước, gửi chunk binary tối đa 64 KiB và chờ ACK từng chunk; chỉ báo `UPLOAD_COMPLETE` sau khi server kiểm tra size/SHA-256 và đăng ký catalog. Không cần run-catalog.ps1 cho upload. Tên trùng được phép, phân biệt bằng UUID.

Giới hạn mặc định 1 GiB ở mỗi bên, cấu hình bằng `-MaxFileBytes`. File rỗng và tên Unicode được hỗ trợ. Hash sai không công bố file; kết nối gián đoạn giữ `.part` và snapshot INTERRUPTED, nhưng **chưa có reconnect/resume**. Không sửa source trong lúc upload. Xem [luồng, state, API và bằng chứng Phase 3](docs/phase3.md).

## Phase 4: Download file qua TCP (TV3)

Dừng server cũ bằng Ctrl+C, build lại rồi chạy server như trên. Upload một file, lấy UUID từ `UPLOAD_COMPLETE` hoặc `run-client.ps1`, sau đó:

```powershell
New-Item -ItemType Directory -Force runtime/downloads | Out-Null
# Thay <fileId> bằng UUID thực tế, destination là tên file local bạn chọn
./run-download.ps1 -FileId '<fileId>' -Destination runtime/downloads/received.bin -ServerHost 127.0.0.1 -Port 49000
```

Client ghi `.part` và checkpoint, ACK từng chunk, kiểm tra size/SHA-256 rồi mới công bố destination và báo `DOWNLOAD_COMPLETE`. File rỗng/Unicode được hỗ trợ. **Không ghi đè:** destination phải chưa tồn tại, kể cả khi có tiến trình tạo nó giữa chừng. Parent phải tồn tại và ghi được; không dùng tên server để quyết định local path.

Publish độc quyền yêu cầu filesystem hỗ trợ hard link (đã kiểm thử Windows NTFS); filesystem không hỗ trợ sẽ báo lỗi an toàn. EOF/timeout giữ partial INTERRUPTED, chưa tự resume. Xem [API, giới hạn và kiểm thử Phase 4](docs/phase4.md).
