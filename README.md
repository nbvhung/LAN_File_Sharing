# Chia sẻ file trong mạng LAN

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

Yêu cầu JDK 21+ và Maven 3.9+. Từ thư mục project, trong PowerShell chạy `./build.ps1 validate`, `./build.ps1 test` hoặc `./build.ps1 package`. Script đặt Maven repository tại `.m2/repository` trong project; thư mục này đã được bỏ qua bởi Git. Có thể chạy trực tiếp từ `source/` bằng `mvn "-Dmaven.repo.local=../.m2/repository" validate`. Đã có TCP server skeleton và LIST_FILES; truyền file và Swing chờ TV2/TV3 triển khai.

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

Dừng bằng Ctrl+C. Từ Phase 2, server đọc catalog thực tế từ thư mục runtime; chỉ trả danh sách rỗng khi chưa đăng ký file hợp lệ. Chưa triển khai truyền file. Test `TcpServerTest` vẫn kiểm tra nền tảng TCP với catalog rỗng và nhiều kết nối.

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
