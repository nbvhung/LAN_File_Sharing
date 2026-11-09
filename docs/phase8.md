# Phase 8 — Java Swing Desktop Client (TV4)

## A. Phạm vi và kết quả

Giao diện Swing thuộc **TV4** theo phân công của người dùng. Bản hoàn thiện được triển khai tại `feature/tv4-ui-discovery`, fast-forward từ main `328af79` sau khi UDP discovery đã được tích hợp. TCP client/transfer services vẫn thuộc TV3; quy tắc phân công đã được ghi vào README và AGENTS.md để các lần làm tiếp không chọn nhầm nhánh.

Đã tích hợp các tính năng hiện có: Discover, chọn server, kết nối IP/port thủ công, LIST_FILES, upload/download, tiến độ, xác minh hash, ngắt/reconnect/resume và log. Không thay wire protocol, không viết lại TcpClient, UploadService, DownloadService hoặc UDP DiscoveryClient.

Cửa sổ **LAN File Sharing** có phần kết nối và ba tab **Server files**, **Transfers**, **Activity log**. Khi bắt đầu upload/download/resume, tự mở Transfers một lần; người dùng vẫn có thể xem tab khác trong lúc worker chạy. Connect thành công mở Server files. Tabs giữ bảng và progress đủ diện tích khi resize, tránh xếp ba bảng dọc khiến Transfers bị ép mất hàng khi thêm Discover.

## B. File tạo mới/chỉnh sửa

Dưới `source/client/src/main/java/vn/edu/lanfileshare/client/ui/`:

| Thành phần | Thay đổi |
| --- | --- |
| `ServerDiscovery`, `ServiceServerDiscovery` | Adapter mới, gọi DiscoveryClient đã có, giữ current search để hủy/close socket UDP |
| `DownloadFileName` | Gợi ý basename an toàn, giữ extension hợp lệ; fallback tên UUID khi có path/control/Windows device name |
| `ClientController` | Điều phối discover/select/cancel trên worker; đóng search khi đóng window; giữ busy đến khi Cancel hoàn tất |
| `ConnectionPanel` | UDP port, Discover, Cancel search, JComboBox endpoint; giữ nhập IP/TCP port thủ công |
| `UiSnapshot`, `UiState` | Kết quả discovery immutable, server được chọn, trạng thái Discovering; giữ constructor snapshot cũ |
| `MainFrame` | Wiring các action, tabs, chọn tên download và cấu hình UDP |
| `ClientSwingMain` | Thêm UDP port tùy chọn ở tham số thứ 4, giữ ba tham số cũ |
| `TransferItem`, `TransferPanel` | Progress Interrupted theo offset đã lưu, tốc độ dừng về 0; Resume chỉ enabled khi đúng endpoint gốc |

Test mới: `DiscoveryControllerIntegrationTest`, `DiscoveryUiModelsTest`. Cập nhật `DesktopSmokeTest` để thao tác Discover, chọn server, Connect và file chooser thật. Cập nhật `run-swing.ps1`, README, AGENTS.md và tài liệu này. Không sửa POM, common protocol hoặc server/network/transfer/discovery backend.

## C. Kiến trúc, trạng thái và luồng

```text
ConnectionPanel -> ClientController (EDT-owned presentation state)
                -> single worker -> ServiceServerDiscovery -> DiscoveryClient
                -> immutable results -> invokeLater -> JComboBox
User selects result -> IP/port fields -> user clicks Connect
                    -> ServiceClientBackend -> existing TcpClient -> LIST_FILES
Upload / Download / Resume -> existing services -> callbacks -> EDT -> Transfers
Cancel / window close -> control executor -> close current UDP/TCP socket
```

- Network I/O, enumeration interface, hash và file I/O lớn chạy ngoài EDT. Một worker xử lý tuần tự các thao tác; không thể vừa Discover vừa Connect/Refresh/truyền file hoặc mở transfer thứ hai.
- Discover nhận UDP port 1..65535; mặc định 49001. Service giữ policy v1 800 ms/lần, tối đa 3 lần, broadcast theo interface. Adapter chỉ gọi API có sẵn.
- Trong Discovering, Connect/Upload/Download/Refresh và chọn endpoint bị khóa; Cancel search được bật. Khi có kết quả, combo vẫn ở placeholder, **không tự chọn hoặc kết nối dù chỉ có một server**.
- Người dùng chọn một endpoint thì IP/TCP port mới được điền. Vẫn phải bấm Connect. Sửa IP/TCP port thủ công sẽ bỏ lựa chọn combo, không tự ghi đè nội dung người dùng vừa nhập.
- Không tìm thấy server: trạng thái Disconnected, thông báo dùng IP thủ công, Connect vẫn sử dụng được. Lỗi discovery: Failed và thông báo fallback, không làm hỏng chức năng TCP.
- Khi đã Connected, Discover/UDP port/combo bị khóa; Disconnect trước khi tìm/chuyển server. Cách này tránh để việc chọn discovery vô tình đổi phiên đang truyền hoặc resume.
- Cancel chạy trên control executor, đóng search hiện tại và không gửi thông điệp CANCEL lên wire. Giữ busy đến khi control task hoàn tất, tránh Cancel cũ đóng socket của search mới. Kết quả của search đã hủy không được đưa vào combo; có thể tìm lại bằng instance DiscoveryClient mới.
- Đóng window khi Discovering không hỏi giữ checkpoint vì chưa có transfer. Search bị đóng; kết quả muộn không render lên window đã dispose. Đóng trong transfer vẫn dùng xác nhận giữ checkpoint và cơ chế interruption cũ.

UI phân biệt Disconnected, Discovering, Connecting, Connected, Uploading, Downloading, Verifying, Interrupted, Resuming, Completed và Failed. Nhãn kết nối hiển thị Connected khi socket đang dùng; trạng thái tác vụ nằm ở footer và bảng transfer.

LIST_FILES, hash verification, no-overwrite và stop-and-wait giữ nguyên service. Sau upload thành công tự refresh danh sách. Download chỉ gợi ý tên basename hợp lệ; người dùng chọn full path, service từ chối ghi đè file cũ. Tên chứa đường dẫn, ký tự điều khiển, ký tự Windows không hợp lệ hoặc CON/NUL/COM1/LPT1... dùng `download-<fileId>.bin`.

Transfer chỉ Completed/100% sau khi service trả thành công. Upload chờ TRANSFER_COMPLETE của server; download theo v1 tự verify/publish rồi gửi COMPLETE, không thêm ACK mới. Interrupted giữ ID/checkpoint/bytes, percentage khớp offset đã ghi, speed hiển thị 0. Resume chỉ bật khi reconnect đúng host/port gốc; controller vẫn validate độc lập.

## D. Build và chạy

Tại root repository:

```powershell
.\build.ps1 compile test package
# Terminal 1: TCP và UDP trên các card mạng
.\run-server.ps1 -BindAddress 0.0.0.0 -Port 49000 -UdpPort 49001
# Terminal 2
.\run-swing.ps1 -UdpPort 49001
```

Trong cửa sổ: Discover -> chọn endpoint -> Connect. Sau đó dùng Server files để Upload/Download, Transfers để xem progress và Resume, Activity log để xem diễn biến.

UDP port khác mặc định: đổi cả server `-UdpPort` và ô UDP port trên UI hoặc `run-swing.ps1 -UdpPort`. TCP port được lấy từ kết quả discovery; vẫn có thể nhập IP/TCP port thủ công. Discovery không thay thế việc mở firewall cho TCP; UDP và TCP là hai cổng riêng.

Entry point: `vn.edu.lanfileshare.client.ui.ClientSwingMain [host] [tcpPort] [checkpointDirectory] [udpPort]`. Script giữ cơ chế Unicode-safe launcher đã có. API entry point mặc định checkpoint ở user home; script mặc định runtime/client. Không thêm dependency UI ngoài Java Swing.

## E. Kiểm thử thực tế

Đã chạy compile; bộ test UI tập trung; toàn reactor `build.ps1 compile test package`. Kết quả full: **168 test PASS**, không failure/error/skipped, gồm common 19, server 33, client 116. BUILD SUCCESS lúc `2026-11-09T23:52:22+07:00`, 02:06 phút, JDK 23 compile release 21. Log: `runtime/ui-complete-build.log`, test UI ban đầu: `runtime/ui-discovery-tests.log`, JUnit reports: `source/*/target/surefire-reports/`.

Sau chỉnh nhỏ nhãn trạng thái và cách trình bày, đã chạy lại package cùng `DiscoveryControllerIntegrationTest,DiscoveryUiModelsTest,DesktopSmokeTest,UiModelsTest`: **13 test PASS**, BUILD SUCCESS lúc `23:54:25+07:00`. Log `runtime/ui-final-check.log`.

| Kiểm tra | Bằng chứng |
| --- | --- |
| Discover nhiều server | Hai UDP responder và TCP server thật, port động; không tự chọn, không có kết nối TCP trước khi caller Connect |
| Endpoint được chọn | Chọn đúng server thứ hai, upload vào catalog thứ hai, server thứ nhất vẫn rỗng |
| Discovery -> transfer | Upload/download 1 MiB, byte-for-byte và SHA-256 khớp |
| Không có phản hồi/lỗi interface | UI về trạng thái có thể nhập IP; TCP LIST_FILES thủ công vẫn hoạt động; có thể tìm lại |
| Cancel | Gói request thật đã đến UDP peer rồi mới hủy; không báo Failed giả, không giữ kết quả hủy, tìm lại được |
| Close đang tìm | Đóng receive UDP, dispose window/test view, không render kết quả muộn |
| EDT | Gate ở worker có xác định, EDT vẫn xử lý heartbeat; thao tác Connect/Upload/Refresh chồng lấn bị chặn |
| Model/view | Combo không auto-select, sửa IP không bị render ghi đè, các nút đúng trạng thái, tên file unsafe có fallback, Resume chỉ cho endpoint gốc |
| JFrame thật | Click Discover -> chọn combo -> Connect -> JFileChooser upload/download; verify 1 MiB; resize 1000x680 và 1180x900; Disconnect và close |
| Regression | Các test TCP/UDP/backend cũ, controller upload 100 MiB và resume upload/download 10 MiB vẫn PASS |

Test mới dùng service/UDP/TCP thật; provider targets localhost giúp chạy có xác định, không thay networking bằng mock. Fault injection chỉ dành cho lỗi interface và gate điều phối worker. Không dùng Thread.sleep ngẫu nhiên để tạo gián đoạn.

Desktop marker: `SWING_DESKTOP_PASS display=true discoverySelection=true uploadDownloadBytes=1048576 byteForByte=true`. Ảnh content của cửa sổ thật: `source/client/target/swing-client-complete.png`. Test desktop tự skip nếu headless, còn controller/model tests vẫn chạy được; trên lần full ở đây desktop có display và không skip.

## F. Checklist demo và giới hạn

1. Server bind 0.0.0.0, chạy UI; bấm Discover, kiểm tra Discovering và UI vẫn thao tác/resize được.
2. Chọn đúng IP/card mạng mong muốn rồi Connect; nhiều server phải có lựa chọn rõ ràng. Không có server thì nhập IP/port thủ công.
3. Upload file, kiểm tra tab Transfers tự mở và chỉ Completed sau xác nhận; trở lại Server files để Download.
4. Chọn tên đích mới, kiểm tra file cũ không bị ghi đè; mở Activity log để xem transfer ID/hash/status.
5. Ngắt trong transfer đủ lớn; reconnect đúng server và Resume. Kết nối server khác thì nút Resume cho hàng cũ phải bị khóa.
6. Thử Cancel search, Discover lại, đóng window khi đang tìm; kiểm tra không có process bị treo.
7. Kiểm thử thủ công thêm trên hai máy LAN, firewall/AP isolation, adapter VPN, ACL từ chối ghi thực tế và DPI màn hình dùng báo cáo. Kết quả automated ở đây dùng loopback và targets có kiểm soát, không tự khẳng định mọi LAN đều cho broadcast qua.

Lịch sử transfer của UI hiện nằm trong cùng lần mở cửa sổ; chưa có màn hình import checkpoint sau khi mở lại app. Checkpoint backend vẫn giữ trên đĩa. Session server chỉ trong cùng tiến trình, TTL 24 giờ; không phục hồi sau server restart. Không thêm HTTP, CANCEL hoặc protocol mới. Upload chưa có callback riêng ngay khi server bắt đầu hash cuối nên UI giữ Uploading trong lúc chờ xác nhận, không đoán đã thành công.

## G. API cho lần tích hợp tiếp theo

- `ClientController.discover(String udpPort)`, `selectServer(DiscoveredServer)`, `cancelDiscovery()` gọi trên EDT.
- `UiSnapshot.discoveredServers()` và `selectedServer()` là dữ liệu immutable cho view; constructor tám tham số cũ giữ tương thích.
- `ServerDiscovery` là ranh giới điều phối UI; `ServiceServerDiscovery` dùng DiscoveryClient và BroadcastTargets đã có, chỉ giữ lifecycle của search hiện tại.
- `MainFrame(backend, discovery, host, tcpPort, udpPort)` hỗ trợ cấu hình/test; constructor cũ vẫn dùng discovery mặc định.
- Các API connect/refresh/upload/download/resume và ProgressListener hiện có được giữ nguyên. Không có stream, FrameCodec hoặc FILE_CHUNK đi vào Swing panels.

## H. Kết luận

**READY cho giao diện tích hợp các chức năng hiện có**, với kiểm thử LAN/DPI thủ công và giới hạn persistence nêu trên. Thay đổi được triển khai trên nhánh TV4.
