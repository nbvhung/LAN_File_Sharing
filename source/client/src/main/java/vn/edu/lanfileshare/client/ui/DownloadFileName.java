package vn.edu.lanfileshare.client.ui;

import java.util.Locale;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;

/** A basename suggestion only; the user still chooses the complete destination path. */
final class DownloadFileName {
    private DownloadFileName() {
    }

    static String suggest(FileInfo file) {
        String name = file.name();
        if (name == null || name.isBlank() || name.contains("..") || name.endsWith(".") || name.endsWith(" ")
                || name.chars().anyMatch(ch -> Character.isISOControl(ch) || "<>:\"/\\|?*".indexOf(ch) >= 0)) {
            return fallback(file);
        }
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
