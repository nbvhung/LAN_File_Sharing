# UDP Server Discovery

## A. Phạm vi

Triển khai tại `feature/tv4-ui-discovery`, fast-forward từ main `60cdf3d`. TV4 phụ trách discovery theo README. Giữ nguyên hợp đồng v1: ASCII UDP `LANFS_DISCOVER/1 <nonce>` và `LANFS_HERE/1 <nonce> <tcpPort>`, tối đa 128 byte, nonce 32 hex chữ thường. Không sửa TCP framing, JSON DTO, Upload/Download/Resume hoặc Swing.

Đã có UDP responder chạy cùng server TCP; client tìm qua broadcast của các interface, trả nhiều endpoint không trùng và chỉ kết nối TCP khi caller chọn. Nhập IP/TCP port thủ công vẫn dùng nguyên các entry point hiện có.

## B. File tạo mới/chỉnh sửa

Các package Java dưới `source/<module>/src/{main,test}/java/vn/edu/lanfileshare/`:

| Module | Tạo mới | Chỉnh sửa |
| --- | --- | --- |
| common | test `protocol/DiscoveryProtocolTest` | `protocol/DiscoveryProtocol`: thêm encode/parse nghiêm ngặt, giữ constants cũ |
| server | `discovery/DiscoveryConfiguration`, `discovery/DiscoveryServer`; test `discovery/DiscoveryServerTest` | `ServerMain`: bootstrap, optional UDP, shutdown |
| client | `discovery/DiscoveryClient`, `DiscoveredServer`, `DiscoveryTarget`, `DiscoveryOptions`, `BroadcastTargets`; `DiscoveryMain`; test `discovery/DiscoveryIntegrationTest` | Không sửa networking/transfer/UI hiện có |
| root | `run-discovery.ps1`, `docs/phase7.md` | `run-server.ps1`, `README.md` |

Không thêm dependency hoặc sửa POM.

## C. Luồng và lifecycle

```text
Client: enumerate IPv4 interfaces, create random nonce
  UDP broadcast/unicast LANFS_DISCOVER/1 <nonce>
Server: validate entire datagram, unicast reply to source IP/port
  UDP LANFS_HERE/1 <same nonce> <actual TCP listener port>
Client: verify version/nonce/port; IP = packet source; deduplicate IP + TCP port
Caller chooses an endpoint
  TCP connect(selected IP, selected port) -> LIST_FILES -> FILE_LIST
```

### Validation chung

`DiscoveryProtocol` kiểm tra đúng byte offset/length, ASCII, toàn bộ format, version, nonce và port 1..65535. Không trim hoặc chấp nhận newline, extra field, IP tự khai báo trong payload, dấu cộng/trừ hay số thập phân. Nonce mới gồm 16 byte SecureRandom rồi encode thành 32 hex chữ thường; mỗi lần thử dùng nonce mới, loại phản hồi cũ của lần trước.

Server/client nhận vào buffer **129 byte**: byte thứ 129 giúp loại gói lớn hơn giới hạn ngay cả khi OS truncate datagram lớn. Tạo packet mới với đúng buffer length mỗi lần nhận, tránh gói ngắn trước đó làm giới hạn gói tiếp theo. Không thêm newline hoặc dùng JSON cho UDP. TCP vẫn dùng FrameCodec như trước.

### Server

`DiscoveryConfiguration(bindAddress, udpPort, tcpPort)` cho phép UDP 0 để test port động; TCP phải là port listener thực 1..65535. Constructor chỉ định bindAddress có default UDP 49001/TCP 49000. Bootstrap truyền `TcpServer.port()` nên khi TCP cấu hình 0, quảng bá đúng port đã được OS cấp, không quảng bá 0 hoặc hardcode 49000.

`DiscoveryServer` có một thread daemon riêng, không chạy receive trên vòng accept TCP và không giữ khóa transfer/catalog. Bind trong constructor, `start()` một lần; `stop()`/`close()` đóng DatagramSocket để đánh thức receive và join worker. Close lặp lại an toàn, close trước start cũng giải phóng port. Packet sai bị bỏ, không phản hồi. Log không chứa toàn bộ dữ liệu packet.

ServerMain giữ sáu tham số cũ; thêm optional thứ 7 UDP port, thứ 8 discovery enabled. Script tương ứng `-UdpPort 49001` và `-DisableDiscovery`. UDP mặc định bật. Nếu bind UDP thất bại, log cảnh báo và tiếp tục phục vụ TCP. Shutdown hook/try-with-resources đóng UDP cùng TCP và transfer coordinator. Nếu không muốn discovery, dùng DisableDiscovery; không cần thay địa chỉ TCP client.

### Client và nhiều interface

`BroadcastTargets` liệt kê interface đang up, không loopback; lấy từng IPv4 interface address có broadcast address. Mỗi target giữ cả local source IP và destination broadcast, nên nhiều card có thể gửi từ đúng interface. Không tự loại adapter virtual/VPN vì chúng có thể là mạng người dùng muốn dùng. Thêm limited broadcast 255.255.255.255 như fallback, không dựa riêng địa chỉ này.

Một DatagramSocket cho mỗi source IP được dùng trong search; không tạo thread nhận cho từng card. Round-robin receive với slice tối đa 20 ms chia sẻ một **deadline tuyệt đối cho cả lần thử**, không phải 800 ms nhân số card. Packet sai hoặc nhiều packet liên tiếp không kéo dài deadline. Mặc định ba lần thử, 800 ms/lần; có thể cấu hình ngắn hơn qua DiscoveryOptions nhưng không vượt giới hạn v1. Khoảng 2,4 giây chờ mạng không tính khởi động JVM, khởi tạo entropy và đọc thông tin card mạng.

Bind/send lỗi ở một interface được log; interface khác vẫn được thử. Nếu không bind/gửi được qua bất kỳ target nào, trả IOException rõ ràng để caller dùng TCP trực tiếp. Hết thời gian không có server thì trả list rỗng. Kết quả immutable, sắp theo IP/port và loại trùng theo **địa chỉ nguồn UDP + TCP port**. Hai IP khác nhau của cùng máy có thể xuất hiện thành hai endpoint vì protocol không có server ID để nhận diện alias.

DiscoveryClient là API đồng bộ, không mở socket TCP, không tự chọn server dù chỉ có một kết quả. Một instance cho phép một search tại một thời điểm; có thể tìm lại sau khi search xong. `close()` từ thread khác hủy receive, giải phóng socket; instance đã close không được tái sử dụng. Caller/UI phải gọi API trên worker; phần này chưa sửa giao diện Swing.

## D. Lệnh build/run/test đã thực thi

```powershell
.\build.ps1 compile
.\build.ps1 test '-Dtest=DiscoveryProtocolTest,DiscoveryServerTest,DiscoveryIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false'
.\build.ps1 compile test package
```

Chạy demo localhost, hai terminal tại root:

```powershell
# Terminal 1
.\run-server.ps1 -BindAddress 127.0.0.1 -Port 49000 -UdpPort 49001
# Terminal 2: chỉ tìm, không mở TCP
.\run-discovery.ps1 -TargetHost 127.0.0.1 -UdpPort 49001
# Lựa chọn tường minh rồi LIST_FILES qua TCP
.\run-discovery.ps1 -TargetHost 127.0.0.1 -UdpPort 49001 -ConnectIndex 1
```

Trên LAN, server bind 0.0.0.0 và client dùng `.\run-discovery.ps1` không truyền TargetHost. Đọc danh sách, chọn endpoint để chạy `run-client.ps1 -ServerHost <IP> -Port <TCP port>` hoặc Swing hiện có. CLI discovery dùng script/java-launch đã có để truyền Unicode args an toàn. Không có auto-connect ngầm.

## E. Kết quả thực tế

**PASS — 159 test, 0 failure/error/skipped:** common 19, server 33, client 107. Bao gồm 141 test cũ và 18 test discovery mới. Full reactor parent/common/server/client/experiments BUILD SUCCESS lúc `2026-11-09T20:35:21+07:00`, 02:41 phút, JDK 23 compile release 21. Log: `runtime/discovery-build.log`; test riêng: `runtime/discovery-tests.log`; JUnit XML tại `source/*/target/surefire-reports/`.

Test mới bao phủ:

- Wire encode/decode, offset packet, ASCII, nonce/version sai, newline/extra fields, port biên/sai và datagram quá 128 byte.
- UDP localhost, đúng nonce/TCP port, không trả lời request hỏng, nhận valid packet sau malformed/oversized, bind conflict, close trước start, close lặp lại, worker kết thúc và port bind lại được.
- Nhiều responder port động, nhiều source socket, dedup qua ba lần thử; không tự kết nối TCP trước caller chọn.
- Responder socket thật gửi version/nonce/port sai, IP thừa trong payload, datagram lớn và duplicate; client chỉ giữ endpoint hợp lệ từ địa chỉ nguồn.
- Server không tồn tại, đúng số lần thử, timeout, packet flood không kéo dài deadline, close hủy receive, chặn hai search cùng instance.
- Discovery → TCP LIST_FILES thực tế; upload/download binary 1 MiB, discovery trong lúc phiên upload đang mở, verify byte-for-byte và SHA-256.
- Manual TCP vẫn LIST được khi UDP không tìm thấy hoặc UDP bind conflict. Toàn bộ resume/concurrency/Swing tests cũ vẫn PASS.

Smoke JAR và script thật tại `runtime/discovery-smoke-9670bdea/evidence.json`:

| Tình huống | Kết quả |
| --- | --- |
| UDP localhost, chỉ tìm | `DISCOVERED count=1`, IP 127.0.0.1, đúng TCP port động 49771; không chạy LIST |
| `run-discovery.ps1 ... -ConnectIndex 1` | `FILE_LIST protocolVersion=1 ... count=0`, exit 0 |
| Broadcast trên card mạng hiện có của chính máy | Tìm được một endpoint nguồn 26.233.79.35, TCP 49771; exit 0 |
| ServerMain gặp UDP bị chiếm port | TCP vẫn khởi động, `run-client.ps1` LIST_FILES thành công, exit 0 |
| ServerMain discovery disabled | TCP LIST_FILES thành công, exit 0 |

Smoke dùng TCP/UDP port động (UDP 62579 ở trường hợp chính), storage riêng và đã dừng các JVM helper đã xác minh. Java launcher shim trên Windows ban đầu làm harness chỉ dừng launcher và còn JVM con giữ pipe; đã nhận diện đúng process bằng command line/storage marker, dừng riêng ba JVM smoke và sửa harness dùng java.exe thực. Đây là lỗi cleanup harness; không có DiscoveryMain còn chạy khi kiểm tra. Không dừng server của người dùng.

Broadcast self-discovery đã chạy trên adapter hiện có, có thể là adapter virtual/VPN. **Chưa kiểm thử giữa hai máy LAN vật lý**, không dùng kết quả trên để khẳng định firewall/router của mọi LAN đều cho broadcast qua. Runtime/log/evidence bị Git ignore, không phải fixture cần commit.

## F. Giới hạn và kiểm tra LAN thủ công

1. Cùng LAN, server bind 0.0.0.0; cho phép UDP discovery port và TCP service port trên firewall đúng profile. README có rule scoped Private/LocalSubnet. Không tự thay firewall hoặc execution policy toàn máy.
2. Chạy discovery trên máy client khác, kiểm tra IP thuộc card mạng mong muốn rồi kết nối TCP/LIST/upload/download.
3. Nếu dùng Wi-Fi guest/AP isolation, VPN hoặc khác subnet/router, broadcast có thể không tới server. Dùng IP trực tiếp; discovery không yêu cầu broadcast đi qua router.
4. Implementation broadcast là IPv4; IPv6 TCP thủ công hiện có không bị thay đổi. Nonce dùng để đối chiếu response, không phải cơ chế xác thực server.
5. Port discovery tùy chỉnh phải được client biết; protocol không tự tìm mọi UDP port. Cửa sổ tìm ngắn hoặc UDP mất gói có thể bỏ sót endpoint; caller có thể tìm lại hoặc dùng IP thủ công.

Không thêm Swing Discover, HTTP, broadcast xuyên router hoặc cơ chế nhận diện/xác thực server ngoài v1.

## G. API cho UI/phase tiếp theo

```java
try (DiscoveryClient discovery = new DiscoveryClient()) {
    List<DiscoveredServer> servers = discovery.discover();       // UDP 49001, 800 ms x 3
    // Hoặc discovery.discover(customUdpPort).
    // UI cho người dùng chọn một phần tử, sau đó:
    DiscoveredServer selected = servers.get(selectedIndex);
    tcpClient.connect(selected.host(), selected.tcpPort());
}
```

- `DiscoveryClient(DiscoveryOptions)` nhận timeout/attempts giới hạn v1; `discover(List<DiscoveryTarget>)` dành cho localhost/target tùy chỉnh và test.
- `DiscoveryTarget(localAddress, destination)` chọn source IPv4; `unicast(address, udpPort)` để OS chọn route.
- `DiscoveredServer.address()`, `host()`, `tcpPort()` cung cấp endpoint có kiểu rõ ràng.
- `BroadcastTargets.forPort(udpPort)` liệt kê directed/limited broadcast.
- `DiscoveryServer(configuration).start()/stop()/close()`, `port()`, `isRunning()` phục vụ lifecycle; `port()` cần lấy trước close nếu caller muốn dùng lại port đã bind.
- Common `DiscoveryProtocol.request/response/parseRequest/parseResponse` là codec UDP dùng chung.

UI có thể đưa API đồng bộ vào executor hiện có, hiển thị list và chỉ Connect khi người dùng chọn. Discovery failure phải giữ ô nhập IP/port thủ công.

## H. Kết luận

**READY cho UDP Discovery v1**, có bằng chứng unit/integration, CLI localhost, local broadcast và fallback. Giới hạn kiểm thử LAN nhiều máy được ghi rõ phía trên.
