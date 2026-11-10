# Module experiments

Module phụ thuộc client và JUnit. Checkout hiện tại chưa có Java source benchmark (`BenchmarkMain/Runner` không tồn tại); không có lệnh benchmark có thể tái lập được từ source. Đây là thiếu sót IMPORTANT của bản nộp, không mô tả module như đã hoàn thành.

CSV tại `../../statics/results/phase09-*` là dữ liệu còn lưu từ lượt chạy cũ, chưa đủ matrix; không dùng để chứng nhận source hiện tại. Root README và báo cáo release ghi rõ số mẫu thực tế, không nội suy kết quả LAN.

Công cụ hỗ trợ release đặt trong `tools/validate_release.py` để giữ source chương trình trong `source/`. Chạy từ root bằng `python source/experiments/tools/validate_release.py` (Python 3.11+, Windows). Công cụ sao chép source sang runtime riêng không có target, chạy Maven offline bằng cache hiện có, thu Surefire, kiểm tra bytecode và chạy launcher server/discovery/upload/download/resume qua process thật. Nó không chứng minh tải dependency được trên máy mới hoặc có JDK 21 runtime. Log ở runtime, bằng chứng ở statics/results/phase10-*.

`python source/experiments/tools/smoke_swing_launcher.py` kiểm tra entry point/launcher Swing thật và gửi yêu cầu đóng cửa sổ đúng JVM do công cụ tạo. Receipt ở statics/results/phase10-swing-launcher.json; không đóng GUI của phiên khác.

Build module: `mvn -f source/pom.xml -pl experiments -am package`. Khi hoàn thiện benchmark cần dùng production transfer services, đồng bộ 1/4/8 client, timing nanoTime, payload bytes, SHA-256, nhiều lần lặp, tách throughput từng client/batch và ghi CSV thực.
