package vn.edu.lanfileshare.server.storage;

import java.nio.charset.StandardCharsets;

public final class FileNames {
    private FileNames() { }

    public static void validate(String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")
                || name.contains("..") || name.codePoints().anyMatch(Character::isISOControl)
                || name.getBytes(StandardCharsets.UTF_8).length > 255) {
            throw new IllegalArgumentException("Invalid file name");
        }
    }
}
