package vn.edu.lanfileshare.client.ui;

import java.util.Locale;

public final class UiFormat {
    private static final int BINARY_UNIT_FACTOR = 1024;
    private static final String[] BYTE_UNITS = { "B", "KiB", "MiB", "GiB" };

    private UiFormat() {
    }

    public static String bytes(long bytes) {
        if (bytes < BINARY_UNIT_FACTOR) {
            return bytes + " B";
        }
        double value = bytes;
        int unitIndex = 0;
        // Mỗi lần chia cho 1024 chuyển sang đơn vị nhị phân lớn hơn một bậc.
        while (value >= BINARY_UNIT_FACTOR && unitIndex < BYTE_UNITS.length - 1) {
            value /= BINARY_UNIT_FACTOR;
            unitIndex++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, BYTE_UNITS[unitIndex]);
    }

    public static String percentage(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value);
    }
}
