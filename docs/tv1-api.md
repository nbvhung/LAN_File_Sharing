# API TV1 cho bước tích hợp đầu tiên

## common — TV2/TV3 sử dụng

- `Messages` trong `vn.edu.lanfileshare.common.model` chứa các DTO record và `Direction`. Ví dụ: `Messages.UploadInit`.
- `ControlCodec.encode(type, dto)` tạo Frame. `decode(frame, Dto.class)` kiểm tra envelope, version và trường bắt buộc. Caller chọn đúng DTO theo MessageType.
- `MessageValidator.validate(frame)` kiểm tra request truyền file. TV2 tiếp tục kiểm tra trạng thái phiên, offset hiện tại và giới hạn dung lượng.
- `FileChunkCodec` xử lý UUID 16 byte, offset, dataLength và dữ liệu. `FileChunk` sao chép mảng để bảo vệ dữ liệu.
- `FrameCodec` chỉ đọc/ghi frame; caller quản lý vòng đọc và socket. EOF khi đọc frame được báo bằng EOFException.

## server — hợp đồng cho TV2

- Triển khai `storage.FileCatalog.listFiles()` trả metadata của file hoàn tất.
- Triển khai `transfer.TransferService.handle(SessionContext, Frame)` nhận frame đã qua validator; decode DTO theo type rồi gọi service nội bộ.
- `session.id()` là ID kết nối. `transferId` là ID truyền file riêng và phải tồn tại qua reconnect.
- Chỉ TcpServer đọc socket. Service gửi qua `session.send(frame)`; không tự đọc hoặc ghi stream socket.
- `handle` chạy trên worker của kết nối. Không chờ frame tiếp theo trong hàm này: cập nhật state, gửi reply rồi return để vòng đọc nhận ACK/chunk tiếp theo.
- Override `disconnected(sessionId)` để đánh dấu gián đoạn và giải phóng khóa; giữ dữ liệu phục vụ resume.
- Lỗi nghiệp vụ gửi ERROR qua session rồi return. IOException báo lỗi không phục hồi và đóng socket.
- Thay catalog rỗng và service tạm trong ServerMain bằng implementation thật. Bootstrap hiện chưa lưu hoặc truyền file.

## TV3

Kết nối TCP 49000, gửi `ControlCodec.encode(LIST_FILES, new Messages.ListFiles(1, requestId))` qua FrameCodec. Đọc FILE_LIST và decode bằng `Messages.FileList`. Không dùng readLine hoặc trộn stream văn bản với frame.

## TV4

DiscoveryProtocol có cổng và prefix discovery. UDP responder/client chưa triển khai. Benchmark dùng client headless của TV3.


## B? sung Phase 1

- `TcpServer.start()` ch?y accept loop b?t ??ng b?; `stop()`/`close()` ??ng listener,
  c?c socket v? ch? worker k?t th?c. Constructor bind ngay; `port()` h? tr? port ??ng 0.
  `serve()` v?n ???c gi? cho caller c? ch?y v?ng accept ??ng b?. Kh?ng g?i ??ng th?i
  `serve()` v? `start()` tr?n c?ng instance; server ?? ??ng kh?ng t?i s? d?ng.
- `ClientSession` qu?n l? reader v? v?ng ??i m?t socket; `SessionContext` cung c?p writer.
- `TcpClient()` d?ng timeout 30000 ms; constructor `TcpClient(int timeoutMillis)` t?y ch?nh.
  `connect(host, port)`, `listFiles()`, `disconnect()`, `isConnected()`, `close()`.
  C?c thao t?c tu?n t?; l?i I/O/protocol khi trao ??i s? ??ng k?t n?i, caller ch? ??ng reconnect.
- `ControlCodec.validate(frame)` ki?m tra DTO t??ng ?ng type. `requestId(frame)` l?y UUID
  t??ng quan, k? c? khi tr??ng kh?c sai. Kh?ng c? UUID h?p l? th? ??ng socket, kh?ng t?o
  requestId gi? cho response.
- `ProtocolException.code()` v? `fatal()` h? tr? ?nh x? l?i. Version sai: ERROR
  UNSUPPORTED_VERSION r?i ??ng. Request sai c? requestId h?p l?: BAD_REQUEST; I/O
  t? service: IO_ERROR r?i ??ng. Frame/chunk h?ng: ??ng v? log INVALID_FRAME.
- `Messages.FileInfo` l? DTO metadata chung hi?n c?; kh?ng t?o DTO FileMetadata tr?ng l?p.
- Server qu? gi?i h?n socket ??ng k?t n?i v? log SERVER_BUSY, v? ch?a ??c ???c requestId.
  DiscoveryProtocol ch? gi? h?ng s? v1; ch?a c? UDP runtime trong Phase 1.
