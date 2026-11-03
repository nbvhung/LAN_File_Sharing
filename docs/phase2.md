# Phase 2 — File Catalog & Listing

Nhánh: `feature/tv2-upload-server` (TV2 phụ trách server/storage). Nhánh được fast-forward từ `main` để dùng Phase 1. Không đổi wire protocol v1, không thêm type/error code và không triển khai upload/download.

## Lưu trữ và persistence

Mặc định dùng `runtime/server`, cấu hình bằng tham số CLI thứ ba của `ServerMain` hoặc `run-server.ps1 -StorageDirectory`. Đường dẫn tương đối tính từ working directory; script chạy tại repository root. Runtime trong bất kỳ thư mục `source/` nào bị từ chối. Các thư mục được tạo tự động:

```text
runtime/server/
  completed/<fileId UUID>        # bytes, tên hiển thị không dùng làm path
  metadata/<fileId UUID>.json    # chỉ fileId, name, size, sha256
  staging/*.part                # không được công bố
```

Mỗi bản ghi JSON là một `Messages.FileInfo`. Không dùng tên file làm khóa; hai tên trùng có UUID khác nhau. Metadata ghi vào file `.tmp` rồi atomic move thành `.json` trên cùng filesystem. Filesystem không hỗ trợ atomic move sẽ báo lỗi, không ghi đè không an toàn. Không cam kết fsync chống mất điện.

`JsonFileCatalog` kiểm tra metadata lúc khởi tạo và đọc lại đĩa mỗi lần listing. Chỉ chấp nhận filename UUID canonical khớp ID trong JSON, schema v1 hợp lệ, size thực tế và SHA-256 khớp. Bản ghi hỏng, file thiếu, sai size/hash hoặc file symlink bị loại khỏi kết quả và ghi log; không xóa bản gốc để giữ bằng chứng chẩn đoán. Thư mục storage lỗi khiến thao tác thất bại rõ ràng. Không quét completed/staging để tự công bố bytes thiếu metadata; `.part`, `.tmp`, orphan và file chưa đăng ký đều bị bỏ qua.

Phạm vi sử dụng là runtime do server sở hữu, một tiến trình ghi tại một thời điểm. Không sửa bytes/metadata trực tiếp trong lúc server đang xác thực; chống tiến trình local độc hại thay thư mục đồng thời không thuộc phạm vi. Full hash mỗi lần listing ưu tiên nhất quán, chi phí O(tổng bytes của file được liệt kê); catalog lớn có thể chạm timeout 30 giây. Không có cache hash hoặc phân trang trong phase này.

## API cho Phase 3/4

- `StorageConfiguration(Path root)`: cấu hình root, `initialize()`, `completed()`, `metadata()`, `staging()`.
- `FileStorageService(configuration)`: tạo thư mục; `completedPath(UUID)` lấy slot nội bộ từ ID do server cấp; `matches(FileInfo)` kiểm tra bytes thực tế; `validateMetadata(FileInfo)` dùng schema v1.
- `FileStorageService.importVerified(Path localFile, String displayName)`: công cụ quản trị local, copy vào staging, chuyển vào slot UUID, tính hash trên bytes đã lưu, trả metadata **chưa công bố**. Path này không nhận từ TCP/client. Nếu lỗi sau khi lưu bytes, orphan có thể còn lại nhưng không xuất hiện trong LIST.
- `JsonFileCatalog(storage) implements FileCatalog`: `listFiles()` trả snapshot bất biến, `findById(UUID)` chỉ thấy file hợp lệ, `registerCompletedFile(FileInfo)` chỉ ghi metadata sau khi kiểm tra bytes tại slot UUID. UUID đã đăng ký bị từ chối; không ghi đè record.
- Phase upload sau này cần ghi/hoàn tất bytes ở slot UUID, rồi gọi `registerCompletedFile`; chưa có transfer handler trong Phase 2. Việc tính/hash/resume theo chunk thuộc phase sau.
- `TcpClient.listFiles()` giữ API Phase 1 trả `Messages.FileList`, trong đó `files()` là `List<Messages.FileInfo>`. ERROR tương quan được ném dưới dạng `ServerResponseException`, có `code()` và `requestId()`; client đóng kết nối theo hành vi Phase 1.

## Giới hạn FILE_LIST v1

Dispatcher serialize toàn bộ FILE_LIST trước khi gửi. Nếu payload JSON UTF-8 lớn hơn 65536 byte, trả `ERROR SERVER_BUSY` với requestId gốc và thông báo giới hạn. Không gửi FILE_LIST bị cắt, không cắt JSON và không loại bớt file tùy tiện. Socket phía server vẫn dùng được cho request tiếp theo. Đây là cách dùng error code đã có; pagination cần một thay đổi giao thức ở phase khác.

## Chạy thử

Tại repository root, sau khi build:

```powershell
./build.ps1 compile test package

# Tạo dữ liệu mẫu local và đăng ký trước khi khởi động server
New-Item -ItemType Directory -Force runtime/demo | Out-Null
Set-Content -Path runtime/demo/sample.txt -Value 'Hello LAN'
./run-catalog.ps1 -LocalFile runtime/demo/sample.txt -DisplayName sample.txt -StorageDirectory runtime/demo/storage

# Terminal 1
./run-server.ps1 -Port 49000 -BindAddress 0.0.0.0 -StorageDirectory runtime/demo/storage

# Terminal 2
./run-client.ps1 -ServerHost 127.0.0.1 -Port 49000
```

Client in bảng `fileId | name | size (bytes) | sha256`. Dừng server bằng Ctrl+C, chạy lại cùng StorageDirectory rồi gọi client: file vẫn xuất hiện. Chạy `run-catalog.ps1` hai lần với cùng DisplayName để thử tên trùng. Chỉ chạy công cụ local khi server đã dừng; đây không phải upload qua mạng. Không cần database hay dependency mới.

Các script server/catalog dùng chung `scripts/java-launch.ps1` và `CliArguments` để chuyển tham số local thành Base64 UTF-8. Cách này giữ nguyên đường dẫn/tên Unicode trên Windows launcher có lỗi encoding; không gửi Base64 trên TCP. Main class vẫn hỗ trợ tham số positional thông thường khi chạy Java trực tiếp.

## Kiểm thử

`JsonFileCatalogTest` kiểm tra catalog rỗng, thư mục tự tạo, hash biết trước, nhiều file/tên trùng/file rỗng, snapshot bất biến, metadata hỏng, schema sai, UUID không khớp, file thiếu/sai size/sai hash, part/orphan, đăng ký sai/ID trùng và path traversal.

`FileListingIntegrationTest` dùng bytes/metadata trên đĩa, TcpServer và TcpClient thực qua localhost port động, kiểm tra restart server, requestId/version, không lộ path. Test catalog 200 file thực kiểm tra ERROR giới hạn 64 KiB qua raw framed socket và API client, hai request trên cùng socket, không mất/cắt bản ghi. Không dùng mock thay cho bằng chứng end-to-end.

## Kết quả thực thi và bằng chứng

- **PASS:** lần cuối `./build.ps1 compile test package` báo `BUILD SUCCESS` toàn bộ parent/common/server/client/experiments; Maven kết thúc lúc `2026-11-03T22:56:59+07:00`, thời gian 57.875 s.
- **PASS:** 32 test, 0 failures, 0 errors, 0 skipped. Common 10, server 17, client 5. Bằng chứng XML/TXT tại `source/{common,server,client}/target/surefire-reports/`.
- **PASS:** `run-catalog.ps1` thực sự đăng ký nội dung `abc`, `run-server.ps1` khởi động, `run-client.ps1` nhận metadata qua socket thật từ JAR.
- **PASS:** khởi động lại process server trên cùng port 55559, client mới vẫn thấy đúng UUID/name/size/hash. Integration test cũng stop/start `TcpServer` trên cùng port động. Listener bật `SO_REUSEADDR` trước bind để hỗ trợ restart sau kết nối cũ.
- **PASS:** `git diff --check`. Giao thức `docs/protocol.md` và các mã message/error không thay đổi. Thay đổi common chỉ thêm exception local cho payload control quá lớn.
- **Môi trường:** Maven 3.9.11, Oracle JDK 23.0.1; compiler release 21. Không cần dependency mới. Chưa thử hai máy LAN vật lý hoặc runtime JDK 21 riêng.

Bằng chứng CLI từ JAR, trước và sau restart:

```text
Server cycle 1: LAN File Sharing TCP listening on 127.0.0.1:55559
FILE_LIST protocolVersion=1 requestId=de5d2217-61a4-48b3-be9c-4263962bd1f0 count=1
fileId | name | size (bytes) | sha256
107b17bf-b296-4f62-95f3-55dd12aee40d | sample.txt | 3 | ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
Server cycle 2: LAN File Sharing TCP listening on 127.0.0.1:55559
FILE_LIST protocolVersion=1 requestId=4db2e301-fdeb-4da2-ba3b-5b5b2141b1df count=1
107b17bf-b296-4f62-95f3-55dd12aee40d | sample.txt | 3 | ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
PASS: actual metadata survives process restart on the same TCP port; CLI processes released.
```

Trong quá trình kiểm tra đã phát hiện và sửa lỗi launcher Windows làm hỏng Unicode path, và lỗi bind khi restart ngay sau kết nối. Một lần redirect output Maven bằng PowerShell `*>` bị PowerShell coi log stderr là lỗi native do ErrorActionPreference; đã chạy lại lệnh không redirect và toàn bộ build/test/package PASS. Không dùng kết quả lần bị dừng làm bằng chứng nghiệm thu.

## File tạo mới/chỉnh sửa

Các đường dẫn Java dưới đây tính từ package `vn.edu.lanfileshare.server`, `vn.edu.lanfileshare.client` hoặc `vn.edu.lanfileshare.common` tùy module.

| Thao tác | Module/thư mục | File |
| --- | --- | --- |
| Tạo | server/main | storage/StorageConfiguration.java |
| Tạo | server/main | storage/FileStorageService.java |
| Tạo | server/main | storage/JsonMetadataStore.java |
| Tạo | server/main | storage/JsonFileCatalog.java |
| Tạo | server/main | storage/StorageMain.java |
| Tạo | server/main | cli/CliArguments.java |
| Tạo | server/test | storage/JsonFileCatalogTest.java |
| Tạo | server/test | cli/CliArgumentsTest.java |
| Tạo | client/main | network/ServerResponseException.java |
| Tạo | client/test | FileListingIntegrationTest.java |
| Tạo | repository | run-catalog.ps1 |
| Tạo | repository | scripts/java-launch.ps1 |
| Tạo | repository | docs/phase2.md |
| Sửa | server/main | storage/FileCatalog.java |
| Sửa | server/main | network/RequestDispatcher.java |
| Sửa | server/main | network/TcpServer.java |
| Sửa | server/main | ServerMain.java |
| Sửa | client/main | network/TcpClient.java |
| Sửa | client/main | ClientMain.java |
| Sửa | common/main | protocol/ControlCodec.java |
| Sửa | repository | run-server.ps1 |
| Sửa | repository | README.md |

## Luồng hoạt động và kết luận

Quản trị import file local → copy staging → lưu slot UUID → tính SHA-256 → register xác thực lại bytes → atomic ghi JSON. Server startup kiểm tra catalog → client gửi LIST_FILES → đọc JSON và xác minh bytes → serialize FILE_LIST đúng requestId → client parse collection có kiểu và in bảng. Record không hợp lệ bị loại; response quá giới hạn trả ERROR có kiểm soát.

**READY cho Phase 2.** Các giới hạn vận hành về full hash, single writer, fsync và pagination đã nêu ở trên. Không triển khai upload/download hoặc thay đổi giao thức; không commit, push hay merge Phase 2 vào main.
