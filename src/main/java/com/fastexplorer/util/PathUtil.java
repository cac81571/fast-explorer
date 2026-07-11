package com.fastexplorer.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class PathUtil {

    private PathUtil() {}

    public static Path parse(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("empty path");
        }
        return Paths.get(trimmed).toAbsolutePath().normalize();
    }

    public static Path resolveForAccess(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        try {
            return normalized.toRealPath();
        } catch (IOException e) {
            return normalized;
        }
    }

    public static String toDisplay(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    public static boolean isSame(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        String left = toDisplay(a);
        String right = toDisplay(b);
        if (isWindows()) {
            return left.equalsIgnoreCase(right);
        }
        return left.equals(right);
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static boolean isUnc(Path path) {
        return isUnc(path.toAbsolutePath().normalize().toString());
    }

    public static boolean isUnc(String pathText) {
        if (!isWindows() || pathText == null) {
            return false;
        }
        return pathText.startsWith("\\\\");
    }

    public static boolean exists(Path path) {
        try {
            return Files.exists(path);
        } catch (Exception e) {
            return false;
        }
    }
}
