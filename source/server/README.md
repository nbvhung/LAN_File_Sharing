# server — TV1, TV2, TV4

- `network/` (TV1): accept loop, executor giới hạn, session, dispatcher, timeout.
- `transfer/` (TV2): upload/download, ACK offset, resume, SHA-256, khóa phiên.
- `storage/` (TV2): FileCatalog, metadata, `.part`, đường dẫn an toàn, công bố file hoàn tất.
- `discovery/` (TV4): UDP responder.
- `http/` (TV4): HTTP GET danh sách/download, chỉ đọc catalog hoàn tất; mở rộng tùy tiến độ.

Runtime phải có đường dẫn cấu hình riêng; không tạo dữ liệu trong source tree.
