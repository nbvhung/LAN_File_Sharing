package vn.edu.lanfileshare.client.ui;

/** Nhận trạng thái và thông báo từ controller trên EDT để cập nhật giao diện. */
public interface ClientView {
    void render(UiSnapshot snapshot);

    void log(String message);

    void notice(String title, String message, boolean error);

    void closed();
}
