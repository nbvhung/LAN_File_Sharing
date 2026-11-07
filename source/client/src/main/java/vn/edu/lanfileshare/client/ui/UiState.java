package vn.edu.lanfileshare.client.ui;

public enum UiState {
    DISCONNECTED, CONNECTING, CONNECTED, UPLOADING, DOWNLOADING, VERIFYING, INTERRUPTED, RESUMING, COMPLETED, FAILED;

    @Override
    public String toString() {
        String text = name().toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
