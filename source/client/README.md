# Module client

Trách nhiệm: TCP client, dịch vụ truyền file streaming, checkpoint, reconnect/resume, UDP discovery và Swing desktop.

Public API: `TcpClient.connect/listFiles/reconnect/disconnect/interruptConnection`; `UploadService.uploadFile/resumeUpload`; `DownloadService.downloadFile/resumeDownload`; `UploadCheckpointStore`; `ProgressListener`; `DiscoveryClient.discover/close`; `ClientController` và `ServiceClientBackend`. Main GUI: `vn.edu.lanfileshare.client.ui.ClientSwingMain`. Main CLI: `ClientMain`, `UploadMain`, `DownloadMain`, `DiscoveryMain`, `ResumeDemoMain`.

Phụ thuộc common khi chạy; server và JUnit khi test integration. TCP/transfer thuộc TV3; Swing và UDP thuộc TV4 theo AGENTS.md, không phải TV3.

`mvn -f source/pom.xml -pl client -am test`. Sau build root: `./run-swing.ps1 -ServerHost 127.0.0.1 -Port 49000 -UdpPort 49001`. `run-client.ps1` là CLI LIST_FILES, không phải GUI.

I/O/hash chạy ngoài EDT; cập nhật view trên EDT. Disconnect trong khi truyền giữ checkpoint để resume cùng endpoint. Download không ghi đè, parent phải tồn tại và filesystem phải hỗ trợ hard link. Một transfer/UI tại một thời điểm; nhiều cửa sổ tạo nhiều client. Lịch sử UI không khôi phục sau đóng cửa sổ; upload checkpoints cũ chưa có janitor tự xóa. IP thủ công luôn dùng được nếu discovery thất bại.
