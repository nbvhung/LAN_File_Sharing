# Phase 6 — Multi Client & Robustness

## A. Phạm vi và phần đã có

Nhánh `feature/tv1-tcp-server`, fast-forward từ main sau Phase 8 (`1bf90e1`). Server trước đây đã có bounded pool, session riêng, registry lease resume, atomic metadata và TTL 24 giờ. Những điểm thiếu được xử lý trong Phase 6: phản hồi quá tải có requestId hợp lệ, khóa registry/catalog quá rộng, giới hạn thời gian frame/write, structured logging và bằng chứng nhiều transfer thật đồng thời.

Không thay protocol v1, không viết lại upload/download/resume, không phát triển UDP/Swing. Chưa merge/push; chờ kiểm thử của người dùng.

## B. File tạo mới/chỉnh sửa

- Chỉnh `server/ServerMain.java`, `network/TcpServer.java`, `ClientSession.java`, `SessionContext.java`: cấu hình, admission, lifecycle, lỗi và log.
- Tạo `server/network/SocketDeadline.java`: deadline đọc frame/ghi socket.
- Chỉnh `server/transfer/TransferSessionManager.java`: khóa theo entry thay vì khóa toàn registry.
- Chỉnh `server/storage/JsonFileCatalog.java`, `JsonMetadataStore.java`: bỏ khóa toàn catalog khi hash; bảo vệ publish metadata theo UUID.
- Chỉnh `run-server.ps1`, README; tạo báo cáo này.
- Tạo test `client/MultiClientIntegrationTest.java`, `server/RobustnessTest.java`, `server/storage/CatalogConcurrencyTest.java`, `server/transfer/TransferManagerConcurrencyTest.java`.

Các đường dẫn Java tương ứng dưới `source/<module>/src/{main,test}/java/vn/edu/lanfileshare/`.

## C. Threading, khóa và luồng xử lý

### Admission và socket lifecycle

Một accept loop, pool session tối đa **32 worker** mặc định, `SynchronousQueue` không giữ hàng chờ kết nối truyền file. Mỗi socket có đúng một reader/ClientSession và một SessionContext writer riêng. Không tạo thread mới vô hạn.

Khi pool đầy, dùng pool xử lý từ chối riêng tối đa **2 worker**, không queue. Nó đọc một control frame hợp lệ rồi gửi ERROR SERVER_BUSY với chính requestId của client và đóng socket. Không gửi UUID ngẫu nhiên hoặc byte ngoài FrameCodec. FILE_CHUNK/JSON hỏng/peer im lặng không có control request hợp lệ để đối chiếu sẽ bị đóng. Nếu cả pool từ chối cũng đầy thì đóng ngay. Đây là best effort theo v1, không cam kết mọi kết nối quá tải đều nhận ERROR.

Tối đa 32 session truyền bình thường, cộng 2 socket tạm xử lý từ chối và socket mới nhất ở accept loop. Một scheduled executor thread dùng chung cho deadline, không phải một thread timeout trên mỗi client. Deadline overload tuyệt đối **500 ms**, kể cả peer gửi nhỏ giọt hoặc không đọc response. Scheduled tasks bị hủy sẽ được remove khỏi queue.

Client bình thường dùng SO_TIMEOUT mặc định 30 giây cho idle read. `SocketDeadline` bổ sung deadline tuyệt đối cho đọc phần còn lại của frame và cho mỗi lần write, tránh reset timeout bằng slow-drip và tránh write bị treo mãi. Deadline đóng đúng socket đó, worker tự dọn session và giữ partial theo Phase 5. Không áp deadline 30 giây lên toàn bộ transfer: transfer lớn vẫn được truyền qua nhiều frame/ACK.

Stop đóng listener, socket admitted và socket overload, shutdown executor rồi chờ worker có giới hạn. Đóng socket trước khi chờ worker, không interrupt FileChannel đang ghi để tránh biến partial có thể resume thành lỗi ghi do interrupt. Một tác vụ filesystem bị kernel treo có thể vượt thời gian chờ; server log cảnh báo thay vì tuyên bố đã dừng worker đó. Test shutdown xác nhận executor thực sự terminated và bind lại được port.

### Lock strategy

| Thành phần | Chiến lược |
| --- | --- |
| Transfer registry | ConcurrentHashMap; monitor theo Entry/transferId cho claim, release, snapshot và expiry |
| Register/close registry | Một monitor rất ngắn cho put-if-absent hoặc snapshot/clear map; không giữ khi đọc/ghi/hash file |
| Resume | Xác minh nguồn/prefix dưới khóa riêng của entry, không giữ khóa toàn registry |
| Offset/data | Giữ cơ chế synchronized theo TransferSession/DownloadSession đã có; chỉ owner lease hiện tại được dispatch cập nhật |
| Đồng thời claim cùng ID | Một claim thắng, claim khác SERVER_BUSY; release owner cũ không nhả lease của owner mới |
| Catalog | Không khóa toàn list/register trong lúc hash; chỉ metadata completed đã publish mới được đọc |
| Metadata catalog | 64 khóa stripe theo absolute metadata path dùng chung các store instance, kiểm tra trùng ID và atomic publish dưới cùng khóa |
| Transfer metadata | Giữ 64 khóa stripe và atomic snapshot của Phase 5; không thay thứ tự ghi data → metadata → ACK |
| Network | Writer lock riêng SessionContext, không giữ registry/catalog lock trong lúc chờ client ACK hoặc gửi frame |

Danh sách trong lúc nhiều client publish là snapshot yếu: có thể chưa chứa file vừa complete ở thời điểm đọc directory. Refresh sau khi các tác vụ hoàn tất sẽ thấy đủ; không có JSON nửa chừng hoặc file .part được công bố. Khóa stripe có thể va chạm giữa hai ID, chỉ serialize thao tác metadata ngắn, không serialize toàn transfer. Phạm vi là một JVM server trên một storage root; không hỗ trợ nhiều tiến trình cùng ghi root đó.

Cleanup TTL 24 giờ giữ nguyên. Janitor quét mỗi phút; query hết hạn bị từ chối ngay, còn việc xóa staging được janitor/`purgeExpired()` thực hiện. INIT không tự quét disk toàn cục nữa. Disconnect không xóa partial còn TTL. Không phục hồi identity sau server restart.

### Error/logging

Lỗi protocol/nghiệp vụ vẫn dùng code v1. Framing nghiêm trọng đóng riêng socket. IOException service trả IO_ERROR với thông báo chung; RuntimeException chưa dự kiến trả INTERNAL_ERROR có requestId rồi đóng riêng socket, log exception nội bộ. Không trả stack trace hoặc absolute path qua response.

System.Logger cung cấp timestamp. Connect/disconnect ghi sessionId, remote address, elapsedMs, sent/received data bytes. Control events ghi action, type, transferId khi READY/COMPLETE và code khi ERROR. Không log payload/nội dung file; không log ACK/chunk ở INFO để tránh hàng nghìn dòng trên mỗi file. Byte counters là FILE_CHUNK data bytes, không phải số byte TCP thực tế. Log console nội bộ có thể chứa stack trace để debug; client chỉ nhận thông báo đã loại bỏ thông tin nội bộ.

## D. Lệnh build/run/test

```powershell
.\build.ps1 compile test package
# Giữ mặc định 32 socket và timeout 30 giây:
.\run-server.ps1 -BindAddress 0.0.0.0 -Port 49000 -MaxConnections 32 -TimeoutMillis 30000
# Máy client: mở nhiều cửa sổ hoặc trên nhiều máy LAN:
.\run-swing.ps1 -ServerHost 127.0.0.1 -Port 49000
```

Với LAN thay 127.0.0.1 bằng IP máy server, xem hướng dẫn firewall ở README. Không thay đổi chức năng UI. Có thể thử server giới hạn thấp `-MaxConnections 1`, giữ một client đã Connect rồi kết nối client thứ hai.

Chạy riêng integration concurrency trên reactor:

```powershell
.\build.ps1 test '-Dtest=MultiClientIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

Bootstrap Java giữ bốn tham số cũ, thêm optional thứ 5 `maxConnections`, thứ 6 `timeoutMillis`. Giá trị mặc định và API constructor TcpServer cũ được giữ tương thích.

## E. Kiểm thử và bằng chứng

Integration dùng port động, socket thật, file nhị phân 1 MiB có byte 0x00. CyclicBarrier tại callback READY/offset đầu xác nhận 4 hoặc 8 client đã mở transfer đồng thời trước khi cho truyền tiếp; không chỉ submit tuần tự rồi gọi đó là concurrency. Có timeout trên barrier/future và shutdown test executor, không ngắt bằng Thread.sleep ngẫu nhiên.

Các case mới:

1. Một client upload, 4 upload và 8 upload; tất cả dùng cùng basename nhưng nguồn/ID khác, byte-for-byte và SHA-256 đúng, catalog đủ ID duy nhất.
2. Bốn download và bốn upload cùng lúc; hash/bytes đúng ở mọi download và catalog completed hợp lệ.
3. Bốn upload trong lúc bốn client liên tục LIST_FILES, kiểm tra metadata/hash và không có duplicate ID.
4. Giữ bốn upload healthy ở barrier, peer khác gửi invalid length, peer khác gửi chunk offset sai rồi disconnect; healthy vẫn complete và accept loop vẫn nhận LIST.
5. Một upload đã ngắt resume trong khi upload mới đã READY, chỉ gửi remainder.
6. Giới hạn một socket: SERVER_BUSY có requestId hợp lệ, client đầu vẫn hoạt động; overload peer im lặng bị đóng theo deadline.
7. Idle timeout; shutdown với partial active giữ .part, state INTERRUPTED, catalog rỗng, pool terminated và port có thể bind lại.
8. Unit registry: một resume bị giữ ở gate không cản register/snapshot/release của transfer khác. Test lease tranh chấp cùng ID và TTL Phase 5 vẫn được giữ.
9. Hai catalog instance đăng ký cùng ID đồng thời: chỉ một thành công, metadata không bị ghi đè.
10. Socket thật gặp RuntimeException service nhận INTERNAL_ERROR không có path nội bộ; client khác vẫn LIST được. Slow-drip nhanh hơn SO_TIMEOUT vẫn bị absolute frame deadline đóng.

Bằng chứng JSON do test tạo: `source/client/target/phase6-evidence.json`. Kết quả tập trung ban đầu: 9 integration test PASS, 0 failure/error/skipped. Full reactor và số test cuối ghi trong phần kết quả sau khi build hoàn tất. Log build: `runtime/phase6-build.log`; báo cáo JUnit: `source/*/target/surefire-reports/`. Không đưa ra số throughput/benchmark từ các test này.

## F. Giới hạn còn lại

Chưa đo hiệu năng trên 8 máy LAN vật lý; kết quả là concurrency loopback trong một máy. Deadline quá ngắn cấu hình thủ công có thể làm client chậm bị disconnect. Không thêm quota tổng session metadata/disk, authentication hoặc resume qua restart. Lỗi disk/OS kéo dài có thể vượt thời gian shutdown như đã nêu. Không hỗ trợ nhiều server process cùng storage root.

## G. API cho phase tiếp theo

Giữ nguyên FileCatalog, TransferService và transfer/resume API. Thêm `TcpServer.statistics()` với activeConnections, workerThreads, largestWorkerPool, rejectedConnections, terminated để quan sát/lập test; đây không phải protocol message. `purgeExpired()` vẫn là API cleanup, `awaitDetached()` vẫn dùng điều kiện theo entry để kiểm thử không sleep. Hai tham số script mới MaxConnections/TimeoutMillis dùng cho demo/load test sau này.

## H. Kết quả cuối

**READY cho Phase 6.** Đã chạy `.\build.ps1 compile test package`: toàn reactor BUILD SUCCESS lúc `2026-11-07T21:20:23+07:00`, thời gian 02:56 phút. Tổng **141 test PASS**, 0 failures/errors/skipped: common 14, server 29, client 98. Bao gồm 128 test trước đó và 13 test Phase 6 mới. Các test Swing có sẵn cũng chạy lại, không phát triển UI mới.

Kết quả concurrency ghi trực tiếp từ test:

| Scenario | Client đồng thời | Completed/hash | Largest session pool |
| --- | ---: | --- | ---: |
| Upload | 1 | 1/1 đúng bytes/hash | 1 |
| Upload cùng basename | 4 | 4/4 đúng bytes/hash, ID khác nhau | 4 |
| Upload cùng basename | 8 | 8/8 đúng bytes/hash, ID khác nhau | 8 |
| Hỗn hợp | 4 upload + 4 download | Tất cả hoàn tất, hash đúng | Không dùng làm số đo benchmark |

Đây là kiểm thử chức năng/concurrency loopback có barrier, không phải benchmark throughput trên LAN. File .part còn TTL được giữ khi disconnect/shutdown; không lọt vào catalog. Các executor trong fixture đã terminated khi đóng server. Chưa merge/push bản Phase 6.
