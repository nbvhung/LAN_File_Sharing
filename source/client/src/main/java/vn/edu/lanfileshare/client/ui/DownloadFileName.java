package vn.edu.lanfileshare.client.ui;

import java.util.Locale;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;

/** Gợi ý tên file cho hộp thoại lưu, người dùng chọn đường dẫn đích. */
final class DownloadFileName {
    private static final String INVALID_WINDOWS_CHARACTERS = "<>:\"/\\|?*";

    private DownloadFileName() {
    }

    static String suggest(FileInfo file) {
        String name = file.name();
        if (name == null || name.isBlank() || name.contains("..") || name.endsWith(".") || name.endsWith(" ")) {
            return fallback(file);
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (Character.isISOControl(character) || INVALID_WINDOWS_CHARACTERS.indexOf(character) >= 0) {
                return fallback(file);
            }
        }
        // Windows dành riêng một số tên thiết bị, kể cả khi có phần mở rộng.
        int extension = name.indexOf('.');
        String base = (extension < 0 ? name : name.substring(0, extension)).toUpperCase(Locale.ROOT);
        if (base.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
            return fallback(file);
        }
        return name;
    }

    private static String fallback(FileInfo file) {
        return "download-" + file.fileId() + ".bin";
    }
}
