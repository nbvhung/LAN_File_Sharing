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

Yêu cầu JDK 21+ và Maven 3.9+. Từ thư mục project, trong PowerShell chạy `./build.ps1 validate`, `./build.ps1 test` hoặc `./build.ps1 package`. Script đặt Maven repository tại `.m2/repository` trong project; thư mục này đã được bỏ qua bởi Git. Có thể chạy trực tiếp từ `source/` bằng `mvn "-Dmaven.repo.local=../.m2/repository" validate`. Hiện có codec chung và service truyền file phía server; chưa có ứng dụng TCP/Swing chạy hoàn chỉnh.

Phần TV2 đã có service lưu trữ/upload/download/resume và kiểm thử độc lập; xem [README server](source/server/README.md) và [bàn giao TV2](docs/tv2-handoff.md). TCP dispatcher, client và discovery vẫn cần được triển khai/tích hợp bởi các thành viên phụ trách.

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
