# Chia sẻ file trong mạng LAN

## Phase 6 — nhiều client và độ ổn định

Server mặc định nhận tối đa 32 session TCP đồng thời, timeout 30 giây; có deadline cho frame/write và phản hồi SERVER_BUSY có requestId khi quá tải nếu còn gửi được response hợp lệ. Cấu hình khi chạy:

```powershell
.\run-server.ps1 -BindAddress 0.0.0.0 -Port 49000 -MaxConnections 32 -TimeoutMillis 30000
```

Đã chạy compile/test/package: **141 test PASS**. Có bằng chứng 4/8 upload đồng thời, 4 upload + 4 download, cô lập client lỗi, resume song song và shutdown giữ partial. Xem [báo cáo Phase 6: threading, khóa, test và giới hạn](docs/phase6.md). Nhánh triển khai `feature/tv1-tcp-server`; chưa merge bản Phase 6.

## Hướng dẫn chạy trên localhost (Windows PowerShell)

Ứng dụng chạy bằng **Java Swing**, không mở bằng trình duyệt. `localhost` là máy đang chạy ứng dụng; dùng địa chỉ **127.0.0.1**, cổng TCP **49000**. Không truy cập `http://localhost:49000` vì cổng này sử dụng giao thức TCP riêng, không phải HTTP.

### 1. Vào thư mục dự án và kiểm tra Java/Maven

```powershell
Set-Location -LiteralPath 'D:\lập trình mạng\LAN_File_Sharing'
java -version
mvn -version
```

Cần JDK 21 trở lên và Maven 3.9 trở lên trong PATH. Máy hiện tại đã build thành công bằng JDK 23, compile theo Java 21. Nếu lưu repository ở nơi khác, thay đường dẫn trong lệnh Set-Location.

### 2. Build trước khi chạy

```powershell
.\build.ps1 compile test package
```

Chờ thông báo **BUILD SUCCESS**. Lần đầu cần Internet để Maven tải dependency. Khi đã build bản mới nhất, có thể mở server/client ngay mà không cần build lại mỗi lần chạy.

### 3. Terminal 1: chạy server localhost

```powershell
Set-Location -LiteralPath 'D:\lập trình mạng\LAN_File_Sharing'
.\run-server.ps1 -BindAddress 127.0.0.1 -Port 49000
```

Giữ terminal này mở. Server sẵn sàng khi xuất hiện:

```text
LAN File Sharing TCP listening on 127.0.0.1:49000
```

Server mặc định lưu dữ liệu tại `runtime/server`. Dừng bằng **Ctrl+C**. Nếu đang chạy server từ bản code cũ, dừng server cũ rồi chạy lại sau khi build.

### 4. Terminal 2: mở giao diện client

Mở một cửa sổ PowerShell mới, không chạy lệnh này trong terminal đang giữ server:

```powershell
Set-Location -LiteralPath 'D:\lập trình mạng\LAN_File_Sharing'
.\run-swing.ps1 -ServerHost 127.0.0.1 -Port 49000
```

Lệnh mở cửa sổ **LAN File Sharing**. Trên giao diện:

1. Kiểm tra **Server IP = 127.0.0.1**, **TCP port = 49000**.
2. Bấm **Connect**. Khi kết nối thành công, danh sách file tự tải; server mới chưa có file sẽ hiện **No files**.
3. Bấm **Upload file**, chọn một file trên máy. Chờ **Completed / SHA-256 verified**; file sẽ xuất hiện trên bảng.
4. Chọn một hàng trên bảng rồi bấm **Download selected**, chọn nơi lưu và tên file mới. Ứng dụng không ghi đè file đích đã tồn tại.
5. Bấm **Refresh** để tải lại danh sách; bấm **Disconnect** để ngắt kết nối.

Trong workspace hiện có hai file demo để chọn Upload: `runtime/demo/sample-1MiB.bin` và `runtime/demo/sample-100MiB.bin`. Đây là file local bị Git ignore, không có sẵn khi clone repository sang máy khác; có thể chọn file bất kỳ của mình.

Để thử Resume: upload/download file đủ lớn → bấm **Disconnect** khi đang truyền → chờ **Interrupted** → bấm **Connect** lại cùng server → chọn hàng Interrupted trong bảng Transfers → bấm **Resume selected**. Giữ server chạy liên tục; không restart server giữa hai lần kết nối. Lịch sử Resume của UI hiện giữ trong cùng lần mở cửa sổ.

### 5. Kiểm tra localhost bằng CLI nếu cần

Khi server ở Terminal 1 vẫn chạy, có thể kiểm tra LIST_FILES từ terminal khác:

```powershell
.\run-client.ps1 -ServerHost 127.0.0.1 -Port 49000
```

Kết quả `FILE_LIST ... count=...` xác nhận client đã trao đổi request/response với server. Lệnh này chỉ xem danh sách; để upload/download bằng giao diện hãy dùng `run-swing.ps1`.

### Lỗi thường gặp khi chạy

| Hiện tượng | Cách xử lý |
| --- | --- |
| Cannot connect / Connection refused | Chạy server trước; kiểm tra cả hai bên dùng 127.0.0.1 và cùng port 49000. |
| Address already in use | Có server khác đang dùng cổng; dừng server cũ hoặc đổi cả server và client sang cùng cổng mới, ví dụ 49010. |
| Missing JAR / Run package first | Chạy lại `.\build.ps1 compile test package` và chờ BUILD SUCCESS. |
| Không tìm thấy java hoặc mvn | Kiểm tra JDK/Maven và PATH bằng `java -version`, `mvn -version`. |
| PowerShell chặn chạy script | Với script trong repository tin cậy, có thể chạy riêng bằng `powershell -NoProfile -ExecutionPolicy Bypass -File .\run-swing.ps1 -ServerHost 127.0.0.1 -Port 49000`; không cần đổi execution policy toàn máy. |
| Không thấy file trên bảng | Server mới có thể rỗng; upload qua UI rồi Refresh. File chưa hoàn tất không được công bố. |

Để chạy giữa hai máy LAN, dùng server `.\run-server.ps1` (mặc định bind `0.0.0.0`), nhập **IP LAN thực của máy server** trên client và cho phép cổng TCP qua firewall. `127.0.0.1` chỉ dùng khi server và client cùng máy. UDP Discover hiện chưa triển khai.

## Kết nối nhiều máy trong mạng LAN

Mô hình: **máy A chạy Server**, các máy B/C chạy **Swing Client**. Client upload file lên máy A; các client khác Refresh rồi download file đó từ máy A. Không cần chạy server trên từng máy client.

### Máy A — chạy server

1. Cho các máy vào cùng mạng LAN có thể liên lạc với nhau: cùng Wi-Fi hoặc mạng dây qua router/switch. Tránh Wi-Fi khách có chế độ chặn các thiết bị truy cập nhau.
2. Build và chạy server nghe trên các card mạng:

```powershell
Set-Location -LiteralPath 'D:\lập trình mạng\LAN_File_Sharing'
.\build.ps1 compile test package
.\run-server.ps1 -BindAddress 0.0.0.0 -Port 49000
```

Không bind `127.0.0.1` khi muốn máy khác truy cập: địa chỉ đó chỉ nhận kết nối trên chính máy A. `0.0.0.0` dùng để server lắng nghe; không nhập địa chỉ này vào client.

3. Mở PowerShell khác trên máy A và tìm địa chỉ IP:

```powershell
ipconfig
```

Lấy **IPv4 Address** của card Wi-Fi/Ethernet đang kết nối LAN, ví dụ **192.168.1.10**. Không lấy địa chỉ của VPN, card ảo hoặc `127.0.0.1`. Thay IP ví dụ trong các lệnh bên dưới bằng IP thực của máy A.

4. Nếu Windows Firewall chặn kết nối, mở **PowerShell bằng Run as Administrator trên máy A**. Với mạng LAN tin cậy đang dùng profile **Private**, thêm rule:

```powershell
Get-NetConnectionProfile
New-NetFirewallRule -DisplayName 'LAN File Sharing TCP 49000' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 49000 -Profile Private -RemoteAddress LocalSubnet
```

Rule này chỉ cho subnet LAN truy cập cổng 49000 trên profile Private; không cần tắt firewall. Nếu đang dùng profile Public, rule Private sẽ không áp dụng: trên mạng tin cậy của mình, kiểm tra/cấu hình Network profile trong Windows Settings; với mạng trường/cơ quan hãy theo cấu hình của quản trị viên. Không cần port forwarding trên router để dùng nội bộ LAN.

### Máy B/C — chạy client

Mỗi máy cần có source/build ứng dụng và Java phù hợp. Nếu clone repository chưa có JAR, cài JDK 21+ và Maven 3.9+, vào thư mục repository của máy đó rồi build một lần:

```powershell
.\build.ps1 compile test package
```

Sau đó kiểm tra máy client truy cập được TCP server:

```powershell
Test-NetConnection -ComputerName 192.168.1.10 -Port 49000
```

Chỉ khi **TcpTestSucceeded : True**, mở Swing:

```powershell
.\run-swing.ps1 -ServerHost 192.168.1.10 -Port 49000
```

Trong giao diện, kiểm tra Server IP là IP máy A, port 49000 rồi bấm **Connect**. Không nhập `127.0.0.1` ở máy B/C: đó là chính máy client, không phải máy A.

### Chia sẻ file giữa B và C

1. Máy B Connect → **Upload file** → chọn nguồn trên máy B → chờ **Completed / SHA-256 verified**.
2. Máy C Connect tới cùng IP/port → **Refresh** → chọn file vừa upload → **Download selected** → chọn đường dẫn đích trên máy C → chờ Completed.
3. File hoàn tất lưu ở `runtime/server` trên máy A theo fileId; không cần chia sẻ folder Windows hoặc đưa đường dẫn storage cho client. Client chỉ thấy metadata và file completed.
4. Nếu mất kết nối giữa chừng, giữ server trên A chạy, client Connect lại cùng IP/port rồi chọn transfer Interrupted và **Resume selected** trong cùng cửa sổ client.

Server hỗ trợ nhiều máy client đồng thời; mỗi cửa sổ client hiện chạy một transfer tại một thời điểm. Internet chỉ cần cho lần build đầu tải dependency nếu chưa cache, không cần Internet để truyền file trong LAN.

Nếu `TcpTestSucceeded` là False: kiểm tra server còn chạy, bind `0.0.0.0`, đúng IPv4/port, firewall trên máy A, VPN và chế độ client/AP isolation của Wi-Fi. IP máy A có thể thay đổi sau khi đổi mạng hoặc router cấp DHCP lại; chạy `ipconfig` để kiểm tra. Khi đổi cổng server, phải đổi cả client và rule firewall tương ứng. Discovery tự động hiện chưa triển khai, nên nhập IP thủ công.

## Chạy giao diện desktop Swing — Phase 8

```powershell
.\build.ps1 compile test package
# Terminal 1
.\run-server.ps1
# Terminal 2
.\run-swing.ps1
```

Nhập IP/port rồi Connect; chọn Upload file hoặc chọn hàng trên bảng để Download. Khi transfer Interrupted, Connect lại cùng server, chọn hàng trong Transfers và bấm Resume. Giao diện hiển thị progress/speed, verify SHA-256, trạng thái và log; một transfer chạy tại một thời điểm. Không ghi đè file đích có sẵn.

Đã có test cửa sổ thật với file chooser/upload/download 1 MiB và test controller qua socket thật với file 100 MiB, ngắt/resume hai chiều. UDP Discovery chưa có backend nên tạm bỏ qua; dùng IP/port thủ công. Xem [kiến trúc, kết quả test và checklist báo cáo Phase 8](docs/phase8.md). Nhánh triển khai `feature/tv3-client-transfer`.

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

Yêu cầu JDK 21+ và Maven 3.9+. Từ thư mục project, trong PowerShell chạy `./build.ps1 validate`, `./build.ps1 test` hoặc `./build.ps1 package`. Script đặt Maven repository tại `.m2/repository` trong project; thư mục này đã được bỏ qua bởi Git. Có thể chạy trực tiếp từ `source/` bằng `mvn "-Dmaven.repo.local=../.m2/repository" validate`. Đã có LIST_FILES, upload/download và resume TCP; Swing desktop đã có (xem Phase 8).

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
