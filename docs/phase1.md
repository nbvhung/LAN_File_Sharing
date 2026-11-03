# B?o c?o Phase 1 ? TCP Networking & Protocol

## A. T?nh n?ng ho?n th?nh

Gi? nguy?n docs/protocol.md v1: type hex, framing big-endian, payload JSON,
FILE_CHUNK v? quy t?c ACK/resume kh?ng thay ??i. Kh?ng ph?t tri?n upload/download,
resume, UDP runtime ho?c Swing.

- D?ng l?i Frame, MessageType, ErrorCode, DiscoveryProtocol v? codec chunk ?? c?.
- ControlCodec ki?m tra envelope, version, UUID canonical, DTO theo MessageType,
  tr??ng b?t bu?c/null, ki?u s? nguy?n, mi?n gi? tr?, SHA-256, t?n file v? metadata
  t?ng ph?n t? FILE_LIST; t? ch?i JSON sai, duplicate key, trailing token, ?p ki?u.
  transferId c?a ERROR ???c ph?p v?ng m?t.
- ProtocolException cung c?p error code v? c? fatal. L?i frame/chunk ??ng socket;
  version sai tr? UNSUPPORTED_VERSION r?i ??ng. Request sai c? UUID h?p l? tr?
  BAD_REQUEST. L?i I/O service tr? IO_ERROR r?i ??ng. N?u kh?ng ??c ???c UUID h?p
  l? th? ??ng socket, kh?ng t?o response mang requestId kh?c.
- TcpServer bind m?c ??nh 49000 qua ServerMain, c?u h?nh host/port; t?i ?a 32 phi?n,
  timeout 30 gi?y. API start/stop/close; session ri?ng, reader duy nh?t, log v?ng ??i.
- TcpClient c? connect/disconnect, timeout k?t n?i v? ??c, LIST_FILES, ??i chi?u
  requestId, x?c th?c response, ??ng k?t n?i khi exchange th?t b?i.
- ClientMain v? run-client.ps1 cung c?p CLI kh?ng giao di?n.

## B. File t?o m?i/ch?nh s?a trong l??t tri?n khai

C?c ???ng d?n Java d??i ??y ???c t?nh t? package vn/edu/lanfileshare c?a module.

| Module/th? m?c | File | Thao t?c trong l??t n?y |
| --- | --- | --- |
| repository | README.md | B? sung c?ch ch?y Phase 1 |
| repository | run-client.ps1 | T?o m?i |
| docs | tv1-api.md | B? sung API v? ?nh x? l?i |
| docs | phase1.md | T?o m?i b?o c?o |
| source/client | pom.xml | Th?m server v?i scope test cho integration th?c |
| client/main | client/ClientMain.java | T?o m?i |
| client/main | client/network/TcpClient.java | T?o m?i |
| client/test | client/TcpClientTest.java | T?o m?i |
| common/main | common/protocol/FrameCodec.java | ?nh x? l?i frame |
| common/main | common/protocol/ControlCodec.java | Ho?n thi?n validation |
| common/main | common/protocol/ProtocolException.java | T?o m?i |
| common/test | common/protocol/StrictProtocolTest.java | T?o m?i |
| server/main | server/ServerMain.java | D?ng UUID g?c, log l?i shutdown |
| server/main | server/network/TcpServer.java | Lifecycle, qu?n l? t?i nguy?n, gi?i h?n phi?n |
| server/main | server/network/ClientSession.java | T?ch reader/session kh?i accept loop |
| server/main | server/network/RequestDispatcher.java | X?c th?c v? x? l? l?i c? ki?m so?t |
| server/test | server/ProtocolSessionTest.java | T?o m?i |

Workspace ban ??u ?? c? README thay ??i v? nhi?u file untracked. ?? gi? v? d?ng l?i
Messages, FileChunk, FileChunkCodec, MessageValidator, SessionContext, FileCatalog,
TransferService, ControlAndChunkTest, TcpServerTest v? run-server.ps1. Kh?ng reset,
commit, push, merge ho?c ??i branch. Branch l?m vi?c: feature/tv1-tcp-server.

## C. Lu?ng ho?t ??ng

Client connect ? t?o UUID ? ControlCodec.encode(LIST_FILES) ? FrameCodec.write ?
ClientSession ??c FrameCodec ? RequestDispatcher x?c th?c ? FileCatalog r?ng ?
FILE_LIST c? protocolVersion=1, UUID g?c, files=[] ? client x?c th?c/in k?t qu? ?
disconnect. Accept loop server ti?p t?c nh?n k?t n?i m?i. Server stop ??ng listener,
c?c socket v? ch? worker k?t th?c; integration test bind l?i ???c ??ng port v?a ??ng.

## D. L?nh build/run/test th?c thi

```powershell
./build.ps1 compile test package
./build.ps1 clean compile test package
./run-client.ps1 -ServerHost 127.0.0.1 -Port 59346
```

?? ch?y ServerMain v? ClientMain t? JAR b?ng `java -cp <server ho?c client jar;
common jar;Jackson jars> <main class> ...`, server bind localhost port 0. Helper
subprocess ch?y hai CLI client li?n ti?p tr?n c?ng server; kh?ng d?ng mock.

?? ch?y l?i demo t?i repository, m? hai terminal:

```powershell
# Terminal 1
./run-server.ps1 -Port 49000 -BindAddress 0.0.0.0
# Terminal 2; thay IP n?u server ? m?y LAN kh?c
./run-client.ps1 -ServerHost 127.0.0.1 -Port 49000
```

D?ng server b?ng Ctrl+C. Ch?y client nhi?u l?n ?? ki?m tra server ti?p t?c ho?t ??ng.

## E. K?t qu? v? b?ng ch?ng

- PASS: l?n build cu?i `compile test package`, BUILD SUCCESS to?n b? parent/common/
  server/client/experiments, t?ng th?i gian Maven 47.398 s.
- PASS: 17 test, failures=0, errors=0, skipped=0: common 10, server 4, client 3.
  B?ng ch?ng XML/TXT t?i source/{common,server,client}/target/surefire-reports/.
- Bao ph? frame roundtrip/empty/truncated/invalid length/oversized/unknown type;
  JSON/version/UUID/required fields/coercion; chunk roundtrip, max size, negative
  offset, dataLength sai; localhost LIST_FILES, nhi?u phi?n, timeout, port ??ng,
  reset ??t ng?t, l?i protocol v? shutdown gi?i ph?ng listener/socket.
- PASS: CLI d?ng JAR tr? FILE_LIST version=1, UUID v? files=[] hai l?n li?n ti?p;
  server v?n s?ng sau c? hai client disconnect. Xem b?ng ch?ng CLI ? cu?i b?o c?o.
- PASS: git diff --check.
- BLOCKED ri?ng goal clean: maven-clean-plugin:3.2.0 ch?a cache; t?i Maven Central
  b? Permission denied: getsockopt. Kh?ng ?nh h??ng compile/test/package ?? ch?y.
- M?t l?n test idle timeout ban ??u th?t b?i v? JVM kh?i t?o entropy UUID trong
  socket deadline; ?? s?a fixture kh?i t?o UUID tr??c th?i h?n socket v? ch?y l?i
  th?nh c?ng. Kh?ng thay ngu?n random production.

## F. Gi?i h?n c?n l?i

Runtime ki?m th? l? Oracle JDK 23.0.1, Maven 3.9.11; compiler d?ng release 21.
Ch?a ch?y ri?ng tr?n runtime JDK 21 ho?c hai m?y LAN v?t l?. Host/IP c?u h?nh ???c;
kh?ng hardcode ???ng d?n c? nh?n trong source hay script. FrameCodec h? tr? payload
r?ng ? t?ng frame; control JSON r?ng kh?ng h?p l?. Chunk r?ng kh?ng h?p l? theo v1.
Gi?i h?n offset+dataLength<=size c?n context transfer c?a phase sau; codec hi?n ki?m
tra offset kh?ng ?m, tr?n s?, ?? d?i v? gi?i h?n chunk. Qu? t?i ??ng socket v? log
SERVER_BUSY v? ch?a c? requestId ?? tr? response t??ng ?ng.

Helper CLI ??u ti?n qua Oracle javapath tr? k?t qu? ??ng nh?ng timeout khi thu h?i
pipe sau terminate; ki?m tra netstat kh?ng c?n listener. Helper ch?y l?i b?ng JVM
tr?c ti?p ?? ki?m tra k?t qu? v? thu h?i process. Shutdown c? ki?m so?t ???c x?c
minh ri?ng qua integration test TcpServer.stop(), kh?ng suy ra t? terminate process.

## G. API cho phase ti?p theo

- FrameCodec.read/write; ControlCodec.encode/decode/validate/requestId.
- FileChunkCodec.encode/decode; FileChunk; Messages.* (FileInfo l? metadata chung).
- TcpServer(bindAddress, port, maxConnections, timeoutMillis, dispatcher),
  start(), serve(), stop(), close(), port(). Instance d?ng m?t l?n; kh?ng g?i
  start v? serve ??ng th?i. Constructor bind ngay, port 0 d?ng cho test.
- TcpClient(), TcpClient(timeoutMillis), connect(host, port), listFiles(),
  disconnect(), close(), isConnected(). Tu?n t?, reconnect t??ng minh sau l?i.
- FileCatalog.listFiles() cung c?p file ?? ho?n t?t; TransferService.handle v?
  disconnected l? interface t?ch h?p, ch?a tri?n khai truy?n file.
- SessionContext.id()/send(frame); service kh?ng t? ??c socket.
- ProtocolException.code()/fatal(); chi ti?t t?i tv1-api.md.

## H. K?t lu?n

READY cho Phase 1 d?a tr?n compile/test/package v? TCP end-to-end th?c t?.
READY n?y gi?i h?n ? n?n t?ng protocol/TCP v? LIST_FILES r?ng.


B?ng ch?ng CLI l?n ch?y cu?i (exit code helper = 0):

```text
LAN File Sharing TCP listening on 127.0.0.1:57216
CLI attempt 1: exit=0 FILE_LIST protocolVersion=1 requestId=3022d512-4574-4233-9c82-9f6ae5a7ef70 files=[]
CLI attempt 2: exit=0 FILE_LIST protocolVersion=1 requestId=9f350b6c-4681-444f-97c8-e00de391b091 files=[]
PASS: both clients exited; same server still running.
CLI server process released.
```
