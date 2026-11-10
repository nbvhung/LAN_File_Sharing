# Module server

Trách nhiệm: accept TCP, executor có giới hạn, request dispatcher, upload/download/resume, xác minh SHA-256, catalog bền vững và UDP responder.

Public API: `TcpServer.start/serve/close/statistics/metrics`, `RequestDispatcher`, `TransferCoordinator`, `TransferSessionManager`, `JsonFileCatalog.listFiles/findById`, `FileStorageService`, `StorageConfiguration`, `DiscoveryServer.start/close`. Entry point: `vn.edu.lanfileshare.server.ServerMain`.

Phụ thuộc: common (runtime), JUnit (test). `mvn -f source/pom.xml -pl server -am test`. Từ root sau build: `./run-server.ps1 -BindAddress 0.0.0.0 -Port 49000 -UdpPort 49001 -StorageDirectory runtime/server`.

Network thuộc TV1; transfer/storage theo phân công cũ TV2; discovery thuộc TV4. Đây là nhãn vai trò, không phải danh tính thành viên.

Giới hạn: tối đa mặc định 32 session; một transfer/socket; 1 GiB/file; TTL resume 24 giờ trong cùng tiến trình; restart không phục hồi session. LIST_FILES tính lại hash và không phân trang, vượt JSON 64 KiB trả SERVER_BUSY. HTTP chưa triển khai. Không bảo đảm fsync chống mất điện hoặc xác thực người dùng.
