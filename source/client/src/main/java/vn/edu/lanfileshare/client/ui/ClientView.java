package vn.edu.lanfileshare.client.ui;

/** All calls run on the Swing EDT; usable with a headless test view. */
public interface ClientView {
    void render(UiSnapshot snapshot);

    void log(String message);

    void notice(String title, String message, boolean error);

    void closed();
}
