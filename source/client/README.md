# client — TV3, TV4

- `network/` (TV3): TCP client, request/reply, reconnect.
- `transfer/` (TV3): upload/download, `.part` phía client, ACK và tiến độ.
- `ui/` (TV3): Swing; socket, hash và file I/O chạy ngoài EDT.
- `discovery/` (TV4): UDP discovery có timeout và số lần thử giới hạn.

Giao diện luôn cho phép nhập IP/port thủ công khi discovery thất bại.
