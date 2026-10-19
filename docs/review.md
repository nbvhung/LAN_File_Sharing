# Review đề tài

Đề tài có phạm vi và thứ tự ưu tiên hợp lý cho nhóm 4 người: TCP và truyền file là lõi, UDP giúp khám phá server, HTTP chỉ là mở rộng. Cách chia `common/server/client/experiments` cho phép build và kiểm thử độc lập.

## Điểm cần làm rõ trước khi tích hợp

1. **Framing:** bản đề ban đầu chưa nói length có tính byte type hay không, kích thước tối đa, mã type và phiên bản. Các điểm này đã được chốt trong `protocol.md` v1.
2. **Payload và chuỗi:** JSON UTF-8 cho điều khiển; chunk nhị phân phải có cách mã hóa transferId và offset cố định. Không trộn text line với frame nhị phân trên cùng socket.
3. **ACK/resume:** hợp đồng v1 định nghĩa ACK là offset byte tiếp theo đã ghi xong và cập nhật metadata, chưa cam kết fsync. Mỗi phiên kiểm tra transferId cùng file ID/hash/size trước khi tiếp tục; chunk lặp xử lý theo offset.
4. **An toàn file:** server tự tạo file ID, chỉ truy cập qua catalog, chuẩn hóa tên file, chặn path traversal và tên trùng. File `.part` không xuất hiện trong LIST hoặc HTTP.
5. **Giới hạn tài nguyên:** cần giới hạn frame, chunk, số kết nối, timeout, thư mục lưu, dung lượng và số phiên dang dở; dọn phiên tạm theo chính sách.
6. **Đo thực nghiệm:** 1/4/8 client cần cùng điều kiện mạng, file mẫu, thời điểm đo và đơn vị tốc độ; CSV lưu dữ liệu đo thật. 100 MB và 8 client là tiêu chí nghiệm thu, nên thử sớm.
7. **Starter của giảng viên:** tài liệu yêu cầu giữ `INSTRUCTION.md`, nhưng workspace hiện không có file này. Nếu nhận starter, thêm nguyên trạng vào thư mục project trước khi nộp.

## Thứ tự triển khai đề xuất

Chốt giao thức → codec và TCP request/reply → catalog và truyền file → client nền → Swing → hash/resume → discovery → tải thử đồng thời → HTTP. Viết test cho frame lỗi, offset lặp, hash sai và phiên gián đoạn vì đây là các rủi ro tích hợp chính.
