# Module common

Trách nhiệm: hợp đồng TCP/UDP dùng chung; không mở server hoặc giao diện.

Public API chính: `FrameCodec.read/write`, `ControlCodec.encode/decode`, `FileChunkCodec`, `MessageValidator`, `Messages`, `ErrorCode`, `DiscoveryProtocol`, `FileHashes.sha256`, `PrefixHash`, `PayloadMetrics`. Mọi thay đổi wire format phải được phối hợp giữa client/server.

Phụ thuộc: Jackson Databind 2.18.3; JUnit Jupiter 5.12.1 chỉ khi test. Java release 21.

Từ repository root: `mvn -f source/pom.xml -pl common -am test`. Build toàn bộ bằng `./build.ps1 package` để chuẩn bị cache cho launcher Windows.

Giới hạn: control JSON 64 KiB, chunk data 64 KiB, frame tối đa 1 MiB; không cung cấp TLS/xác thực. [Đặc tả](../../docs/protocol.md).
