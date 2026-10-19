# Giao thức LAN File Sharing v1

Đây là hợp đồng v1 cho `common`, `server`, `client`. Đổi wire format hoặc ý nghĩa ACK phải tăng `protocolVersion` và cập nhật test.

## TCP frame

`[length: 4 byte big-endian][type: 1 byte][payload: length-1 byte]`. `length` tính cả byte type, không tính header; hợp lệ `1..1048576`. Đọc đủ và kiểm tra length trước khi cấp phát. EOF giữa frame, length sai hoặc type lạ: đóng kết nối, log `INVALID_FRAME`. Không gửi byte ngoài frame. Một socket xử lý tuần tự, không pipelining ở v1. Server mặc định tối đa 32 socket, timeout 30 giây; quá tải trả `SERVER_BUSY` nếu có thể rồi đóng.

Payload điều khiển là JSON object UTF-8 tối đa 64 KiB. Mọi JSON có `protocolVersion:1`, `requestId` UUID string. Reply lặp `requestId`; frame thuộc transfer dùng requestId của `*_INIT`/`RESUME_QUERY` gần nhất. Thiếu trường hoặc sai kiểu trả `BAD_REQUEST`. Số `size`/`nextOffset` là số nguyên không âm; SHA-256 là 64 hex chữ thường. `transferId` và `fileId` là UUID; server cấp cả hai.

| Type hex | Message | Hướng | Trường JSON khác / payload |
| --- | --- | --- | --- |
| `01` | `LIST_FILES` | C→S | không |
| `02` | `FILE_LIST` | S→C | `files:[{fileId,name,size,sha256}]` |
| `03` | `UPLOAD_INIT` | C→S | `name,size,sha256` |
| `04` | `DOWNLOAD_INIT` | C→S | `fileId` |
| `05` | `TRANSFER_READY` | S→C | `transferId,direction,fileId,name,size,sha256,nextOffset` |
| `06` | `FILE_CHUNK` | gửi→nhận | nhị phân bên dưới |
| `07` | `PROGRESS_ACK` | nhận→gửi | `transferId,nextOffset` |
| `08` | `RESUME_QUERY` | C→S | `transferId,direction,fileId,size,sha256` |
| `09` | `TRANSFER_COMPLETE` | nhận→gửi | `transferId,fileId,size,sha256` |
| `0A` | `ERROR` | hai chiều | `code,message,transferId?` |

`direction` là `UPLOAD` hoặc `DOWNLOAD`. Tên file chỉ là basename, không chứa `/`, `\\`, `..` hay ký tự điều khiển; tối đa 255 byte UTF-8. Server lưu theo fileId, không ghép tên client vào đường dẫn. Tên trùng được phép, phân biệt bằng fileId.

Payload `FILE_CHUNK`: `transferId` UUID 16 byte (hai long big-endian), `offset` signed 64 bit big-endian, `dataLength` signed 32 bit big-endian, rồi đúng `dataLength` byte. `dataLength=1..65536`; file rỗng không gửi chunk. `offset>=0` và `offset+dataLength<=size`. Sai độ dài hoặc số âm: `INVALID_FRAME`, đóng socket.

## Luồng truyền

**Upload:** client tính hash → `UPLOAD_INIT` → server cấp ID, tạo `.part` và metadata → `TRANSFER_READY nextOffset=0` → client gửi một chunk → server ghi → `PROGRESS_ACK` → chunk tiếp theo. Chỉ một chunk chưa ACK. Khi đủ `size` (kể cả file rỗng), server kiểm tra SHA-256, đổi `.part` thành file hoàn tất trong cùng filesystem, cập nhật catalog và gửi `TRANSFER_COMPLETE`. Hash sai trả `HASH_MISMATCH`, không công bố file.

**Download:** client `DOWNLOAD_INIT fileId` → server `TRANSFER_READY nextOffset=0` → client gửi `PROGRESS_ACK nextOffset=0` → server gửi từng chunk và chờ ACK. Client ghi `.part`; khi đủ size, tự kiểm tra hash, đổi tên file và gửi `TRANSFER_COMPLETE`. Hash sai gửi `HASH_MISMATCH`, không đổi tên. Với file rỗng, thực hiện bước kiểm tra/complete mà không có chunk.

ACK `nextOffset` là byte đầu tiên chưa được bên nhận ghi xong. Bên nhận ghi chunk và metadata trước khi ACK; v1 flush ở mức ứng dụng, không cam kết fsync chống mất điện. Chunk đúng `offset == nextOffset` được nhận; chunk lặp nằm hoàn toàn trước `nextOffset` chỉ trả lại ACK; chồng lấn hoặc nhảy offset trả `OFFSET_MISMATCH`. Bên gửi chỉ đọc tiếp từ ACK/READY.

**Resume:** giữ `.part`, metadata, transferId 24 giờ từ lần hoạt động cuối. Client luôn khởi tạo lại phiên TCP bằng `RESUME_QUERY`; server kiểm tra transferId, direction, fileId, size, hash. Upload: server là bên nhận, trả `TRANSFER_READY nextOffset` đã lưu. Download: server trả `TRANSFER_READY` với metadata, client đáp `PROGRESS_ACK` chứa offset `.part` đã lưu rồi server gửi tiếp. Server không gửi chunk trước ACK đầu tiên. File nguồn phía client/server phải còn đúng size/hash; sai trả `TRANSFER_CHANGED`. File đã hoàn tất không resume. Khôi phục sau server restart là mở rộng.

Mã lỗi: `BAD_REQUEST`, `UNSUPPORTED_VERSION`, `INVALID_FRAME`, `NOT_FOUND`, `INVALID_NAME`, `FILE_TOO_LARGE`, `SERVER_BUSY`, `OFFSET_MISMATCH`, `TRANSFER_NOT_FOUND`, `TRANSFER_CHANGED`, `HASH_MISMATCH`, `IO_ERROR`, `INTERNAL_ERROR`. Lỗi framing/version/I/O hỏng đóng socket; lỗi nghiệp vụ giữ socket nếu còn an toàn. Không lộ stack trace/đường dẫn server trong `message`. Giới hạn file mặc định 1 GiB, cấu hình được. LIST/HTTP chỉ thấy file hoàn tất.

## UDP discovery

UDP mặc định port `49001`, TCP `49000`, đều cấu hình được. Request ASCII `LANFS_DISCOVER/1 <nonce>`; response `LANFS_HERE/1 <nonce> <tcpPort>`. Nonce là 32 hex chữ thường; gói tối đa 128 byte. Client kiểm tra nonce/version, lấy IP từ địa chỉ nguồn gói. Timeout 800 ms, tối đa 3 lần thử. Luôn cho nhập IP/port thủ công.

## HTTP tùy chọn

Chỉ GET `/files` trả JSON metadata, GET `/files/{fileId}` trả binary. Dùng catalog file hoàn tất; cổng riêng với TCP frame, không upload và không truy cập `.part`.
