package vn.edu.lanfileshare.client.ui;

import java.util.Locale;

public final class UiFormat {
    private UiFormat() {
    }

    public static String bytes(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        double value = bytes;
        String[] units = { "B", "KiB", "MiB", "GiB" };
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    public static String percentage(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value);
    }
}
