# Final review và release checklist

Ngày kiểm chứng: 10/11/2026, Windows 11 amd64, JDK 23.0.1, Maven 3.9.11. Phạm vi thật: localhost, một máy có desktop display. **Trạng thái: chưa kết luận RELEASE READY.** Core TCP/Swing đã qua kiểm chứng; thông tin nhóm, clean build có dependency mới, JDK 21 runtime/máy mới, LAN và source benchmark chưa đầy đủ.

## Đối chiếu starter và cấu trúc

Đã đọc [starter chính thức](https://github.com/hungdn1701/jnp-project-starter), lấy INSTRUCTION.md nguyên văn từ main vì checkout chưa có file này. Đã so sánh nội dung UTF-8 với response chính thức: bằng nhau, không viết lại. SHA-256 cuối: `054c503a2fb13f09104d4bbd6151d7bce5e36e1a48eca150bcb6e66edd925802`. Giữ nguyên từ đây; lịch sử Git có thể chuyển line endings khi checkout nên kiểm tra nội dung nếu so hash khác OS.

Cấu trúc root bắt buộc có README.md, INSTRUCTION.md, statics/, source/pom.xml và bốn module common/server/client/experiments. Java source nằm trong source/. Công cụ Python kiểm chứng cũng đặt trong source/experiments/tools. docs/, scripts/ và các launcher PS1 là cấu trúc đã có, không tạo root application mới.

Starter quy định tối đa ba sinh viên. TV1–TV4 trong kế hoạch là nhãn trách nhiệm; chưa có dữ liệu tên/MSSV để xác định danh sách thật. Không bịa hoặc quy Git username thành sinh viên.

## Các thay đổi

- Root README tiếng Việt thay nội dung lặp theo phase bằng hướng dẫn bản hiện tại: 29 nhóm nội dung yêu cầu, sơ đồ kiến trúc + Connect/LIST_FILES, upload, download, resume.
- Bốn module README nêu trách nhiệm/public API/dependency/build/test/giới hạn; sửa UI thuộc TV4, bỏ HTTP như chức năng đã triển khai.
- Protocol giữ wire format hiện tại; phần HTTP đánh dấu chưa triển khai.
- Parent POM giữ Java release 21, Compiler 3.13.0/Surefire 3.5.2; pin thêm Clean 3.2.0, Resources 3.3.1, Jar 3.4.1 theo các version Maven đang dùng.
- build.ps1 bổ sung -Offline/-SkipTests. Cờ -o trực tiếp bị PowerShell hiểu nhầm với common parameter; dùng -Offline. Giữ exit code thật khi stderr có Java logger thay vì biến log thành lỗi script.
- Sửa assertion slow-drip deadline để chấp nhận EOF hoặc reset socket trên Windows; vẫn fail nếu read timeout, vẫn kiểm tra deadline <2 giây. Production TCP/GUI/UDP không thay đổi.
- .gitignore bổ sung target mọi module, bytecode/JAR/ZIP/IDE/Python cache; giữ runtime/.m2/part/tmp/log ngoài Git. Ngoại lệ CSV phase09/phase10 để dữ liệu thực có thể được review/nộp.
- Thêm công cụ xác minh checkout sạch, final JUnit CSV/JSON, ảnh Swing thật và báo cáo này. Theo yêu cầu tiếp theo, chốt thay đổi trên `chore/release-documentation` rồi merge cục bộ vào `main`; không push/tag/tạo GitHub release.

## Build và kiểm thử cuối

[Bằng chứng chi tiết](../statics/results/phase10-validation.json), [JUnit từng test](../statics/results/phase10-junit.csv), [build wrapper bản cuối](../statics/results/phase10-launcher-build.json).

Bản sao tại `runtime/release-check-fh5uolk4` được tạo **không có target**. Maven package offline biên dịch lại từ Java source và chạy **168/168 test PASS, FAIL 0, SKIP 0**: common 19, server 33, client 116. Experiments không có Java test/source benchmark nên không cộng test giả. Các class production kiểm tra đều major version **65** (target Java 21). Chưa chạy bằng JVM 21 vì máy chỉ có JDK 17/23; Java 21 target không đồng nghĩa runtime 21 đã kiểm chứng.

Package bản sao sạch dùng version plugin mặc định đúng các version sau đó được pin trong parent. Wrapper trên root với POM cuối được chạy lại `powershell -File build.ps1 -Offline -SkipTests package`, exit 0; lượt này không chạy thêm test, không cộng số lượt test lần nữa.

`mvn clean` offline tại bản sao thất bại vì cache không có Clean Plugin 3.2.0. Đây là **BLOCKED do dependency/môi trường**, không gọi là clean build PASS. Fresh-output build có giá trị xác minh không dựa vào class/JAR cũ, nhưng không chứng minh tải Maven Central trên máy hoàn toàn mới.

### Integration qua process/launcher thật

| Kiểm tra | Expected | Actual | Kết quả |
|---|---|---|---|
| Server launcher | Listen TCP/UDP cấu hình được, storage riêng | Server process tại bản sao sạch; TCP 65041, UDP 64698 | PASS |
| Discovery launcher | Tìm endpoint rồi LIST_FILES khi chọn rõ | DISCOVERED count=1, FILE_LIST count=0, exit 0 | PASS localhost UDP unicast |
| Upload launcher | Truyền binary 100 MiB, verify server | UPLOAD_COMPLETE, 104857600 bytes, exit 0 | PASS |
| Download launcher | Nhận đủ byte, hash khớp nguồn | DOWNLOAD_COMPLETE, SHA-256 đối chiếu độc lập bằng nhau, exit 0 | PASS |
| Resume upload | Ngắt tại 30 MiB, chỉ gửi phần còn lại | before=31457280, after=73400320, total=104857600, same transferId | PASS |
| Resume download | Ngắt tại 40 MiB, chỉ nhận phần còn lại | before=41943040, after=62914560, total=104857600, RESUME_DEMO_PASS | PASS |
| Swing desktop | Mở, discovery/select/connect, upload/download, resize, disconnect/close | DesktopSmokeTest PASS, display thật, 1 MiB hash/bytes khớp | PASS |
| run-swing.ps1 trực tiếp | JVM đúng entry point và cửa sổ có thể quan sát/đóng | JVM ClientSwingMain khởi động; external MainWindowTitle không xác nhận được trong 45 giây; yêu cầu close không kết thúc launcher trong 15 giây, đã dừng đúng JVM do tool tạo | BLOCKED kiểm chứng launcher GUI; cần chạy thủ công |
| UI tải lớn | EDT heartbeat khi truyền 100 MiB | ClientControllerIntegrationTest PASS | PASS |
| Multi-client | 1/4/8 upload, mixed 4 upload + 4 download, isolation | MultiClientIntegrationTest PASS | PASS localhost |
| Error handling | Framing/JSON/offset/hash/permission/timeout bị xử lý | JUnit assertions PASS; lỗi chủ động trong test không coi là lỗi sản phẩm | PASS phạm vi test |
| Shutdown test server | Dừng riêng process thử nghiệm | taskkill /T bị môi trường chặn; xác minh jcmd main/user.dir rồi Stop-Process đúng JVM 5964; cổng thử nghiệm đóng | PASS cleanup thủ công |
| LAN broadcast/firewall | Hai Windows hosts thật | Chưa có máy thứ hai được xác minh | BLOCKED |
| Clean build/máy mới | Maven tải plugin và build từ đầu | Clean Plugin vắng trong cache, không có Internet được xác minh | BLOCKED |

SHA-256 nguồn/bản tải về 100 MiB: `498546e6a3a4a56ad16865f45c6ba1355875be19c9f85dd7b2f622cf9be17b4b`.

Công cụ ban đầu gặp lỗi encoding khi truyền absolute path có dấu cho mvn.cmd: đã đổi cache thành relative path. Helper cũng từng timeout khi taskkill tree bị từ chối; các test/launcher đã trả 0 trước lỗi cleanup. Receipt cuối được phục hồi minh bạch từ các return code quan sát được, Surefire/log/bytecode/files thật; không tự tạo elapsed time. Công cụ hiện lưu receipt trước cleanup và xác minh JVM theo main + thư mục riêng. Không dừng java.exe của phiên khác.

[Receipt kiểm tra launcher Swing](../statics/results/phase10-swing-launcher.json) giữ displayVerified=false và cleanup cưỡng bức; không đổi thành PASS chỉ vì test MainFrame riêng thành công. Chưa xác định đây là giới hạn quan sát desktop của môi trường hay hành vi launcher ngoài test; cần kiểm tra trên desktop người dùng trước release.

## Ảnh thật

[Ảnh cửa sổ Swing](../statics/screenshots/phase10-desktop-localhost.png) lấy từ DesktopSmokeTest của bản sao sạch, đã mở kiểm tra hình. Cửa sổ có hai hàng upload/download Completed, SHA-256 verified, endpoint localhost. Đây là ảnh render cửa sổ thật sau assertions, không phải ảnh minh họa AI và không chứng minh LAN. Cần chụp thêm ảnh LAN trong buổi demo.

## Thực nghiệm còn lưu

CSV phase09 là dữ liệu lịch sử không theo dõi Git trước lượt này; nay có ngoại lệ ignore để review. Thực tế còn **179 mẫu / 43 batch**, toàn bộ success/hash=true và overlap=true. Kế hoạch 234 mẫu/54 batch chưa chạy hết. Với 100 MiB: chỉ có UPLOAD; n=1 có 3 mẫu/3 batch, n=4 có 12 mẫu/3 batch, n=8 có 8 mẫu/1 batch. DOWNLOAD 100 MiB không có dữ liệu.

Median throughput mỗi client UPLOAD 100 MiB lần lượt 9.806, 4.761, 3.404 MiB/s ở 1/4/8 client. Không coi tám client cùng batch là tám lần lặp độc lập, không biến aggregate throughput thành per-client throughput, không suy luận hiệu năng LAN. Environment gốc trong CSV: localhost/shared JVM, Windows 11, JDK 23, heap 256 MiB.

Checkout hiện tại **không có BenchmarkMain/BenchmarkRunner**; module experiments chỉ có POM, README và tool release mới. Không có run-benchmark.ps1, không hướng dẫn lệnh không tồn tại. Cần khôi phục source đã làm trước đó hoặc hoàn thiện module trước khi coi phần thực nghiệm có thể tái lập. Báo cáo phase09-final-junit.csv cũ không đại diện source hiện tại; kết quả phase10 là 168 test từ checkout đang nộp.

## Phân loại final review

### BLOCKER

Không phát hiện blocker core còn lại trong phạm vi đã thực thi: source build/package được, TCP hoạt động, upload/download/hash/resume đúng; README đã có hướng dẫn chạy. Chưa xác minh môi trường mới/LAN/JDK 21 và clean online nên không mở rộng kết luận ra các môi trường đó.

### IMPORTANT

- Thiếu source benchmark; dữ liệu cũ chưa đủ matrix. Phần nghiệm thu phase thực nghiệm chưa hoàn chỉnh.
- Thiếu danh sách thành viên/MSSV/đóng góp thật; chưa thể coi README sẵn sàng nộp hành chính.
- Clean plugin download và JDK 21 runtime/máy mới chưa kiểm chứng.
- LAN thật/broadcast/firewall trên hai máy chưa thực hiện.
- Upload checkpoint client chưa có janitor; cần dọn dữ liệu thử khi không còn dùng.
- LIST_FILES hash lại toàn bộ catalog, thiếu pagination; catalog lớn có thể chậm/timeout/vượt 64 KiB.
- Chưa soak nhiều giờ; không chứng minh tuyệt đối không có leak/race ở mọi điều kiện.
- Log mặc định ra console/GUI, không có file rotation; khi demo tự thu log thực và theo dõi dung lượng.

### OPTIONAL

HTTP browser download, cải thiện thẩm mỹ, TLS/auth như hướng phát triển tiếp, pipelining/tối ưu throughput. Không triển khai thêm các chức năng lớn trong lượt documentation này.

## Release checklist

- [x] Cấu trúc bắt buộc và module dependencies được review.
- [x] INSTRUCTION.md lấy nguyên văn chính thức, đối chiếu nội dung.
- [x] README tiếng Việt, kiến trúc và bốn sequence, build/run/config/firewall/demo có expected.
- [x] Bốn module README phản ánh source.
- [x] Java release 21 và bytecode 65; plugin versions rõ.
- [x] Fresh-output package và unit/integration tests 168 PASS.
- [x] Server + upload/download/resume CLI qua process thật, file 100 MiB hash đúng.
- [x] Swing desktop thật và EDT responsiveness qua JUnit.
- [x] 1/4/8 client và UDP localhost qua integration tests.
- [x] .gitignore loại runtime/cache/artifacts, giữ bằng chứng thực được chọn.
- [x] Có known limitations, danh sách thay đổi và việc thủ công.
- [ ] Clean Maven build có thể tải dependency từ Internet trên máy mới.
- [ ] Chạy trực tiếp bằng JDK 21 runtime.
- [ ] Chạy/đóng run-swing.ps1 trực tiếp trên desktop người dùng, xác minh cửa sổ và không để lại JVM; lượt quan sát tự động riêng chưa PASS.
- [ ] Điền thành viên/MSSV/đóng góp xác thực; ánh xạ vai trò vào tối đa ba sinh viên.
- [ ] Khôi phục source benchmark, hoàn tất matrix nếu là yêu cầu nộp.
- [ ] Demo LAN hai máy, broadcast/firewall và ảnh thật tương ứng.
- [x] Review tập thay đổi và ignore cho commit/merge cục bộ theo yêu cầu; nhánh tích hợp `chore/release-documentation`.
- [ ] Push/tag/GitHub release chỉ sau yêu cầu riêng; kiểm tra remote trước khi nộp.

## Việc nhóm làm trước khi nộp

1. Cung cấp dữ liệu thành viên thật và xác nhận đóng góp; không thêm danh tính suy đoán.
2. Trên máy Windows mới có JDK 21/Maven/Internet: clone URL nhóm, `./build.ps1 clean package`, rồi làm demo theo root README.
3. Khôi phục source experiments từ nhánh/commit thật đã triển khai; chạy benchmark có receipt mới. Không sửa CSV cũ cho đủ matrix.
4. Hai máy cùng LAN tin cậy: cấu hình IP/ports, firewall Private/LocalSubnet, thử discovery và TCP, resume/nhiều client; lưu hash/log/ảnh thật.
5. Giữ INSTRUCTION.md nguyên bản; xem `git status --short`, không add runtime/.m2/target. Các thay đổi được chốt qua nhánh tài liệu và merge cục bộ; nhóm review trước khi push/nộp. `.gitattributes` giữ nguyên byte của INSTRUCTION.md, tránh đổi line endings khi checkout Windows.

Không có thay đổi GitHub từ lượt làm việc này. Kết luận hiện tại là **core demo localhost đã được xác minh, bộ tài liệu cuối đã hoàn thiện trong phạm vi dữ liệu có thật; còn điều kiện thủ công trước khi xác nhận RELEASE READY**.
