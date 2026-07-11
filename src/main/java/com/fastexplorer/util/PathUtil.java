package com.fastexplorer.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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

    public static Path commonRootDirectory(Collection<Path> paths) {
        List<Path> normalized = new ArrayList<>();
        for (Path path : paths) {
            if (path != null) {
                normalized.add(path.toAbsolutePath().normalize());
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("empty paths");
        }
        Path reference = normalized.get(0);
        Path root = reference.getRoot();
        int maxCommon = reference.getNameCount();
        for (int depth = 0; depth < maxCommon; depth++) {
            String segment = reference.getName(depth).toString();
            for (int i = 1; i < normalized.size(); i++) {
                Path other = normalized.get(i);
                if (!sameRoot(root, other.getRoot())
                        || other.getNameCount() <= depth
                        || !segmentEquals(segment, other.getName(depth).toString())) {
                    return buildRootPath(root, reference, depth);
                }
            }
        }
        return buildRootPath(root, reference, maxCommon);
    }

    public static Path resolveHierarchyCopyDestination(Path destDir, Path source, Path hierarchyBase) throws IOException {
        Path normalizedDestDir = destDir.toAbsolutePath().normalize();
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path normalizedBase = hierarchyBase.toAbsolutePath().normalize();
        Path relative = normalizedSource.startsWith(normalizedBase)
                ? normalizedBase.relativize(normalizedSource)
                : normalizedSource.getFileName();
        Path destination = normalizedDestDir.resolve(relative).normalize();
        if (!destination.startsWith(normalizedDestDir)) {
            throw new IOException("コピー先パスが不正です: " + toDisplay(relative));
        }
        return destination;
    }

    private static Path buildRootPath(Path root, Path reference, int nameCount) {
        if (root == null) {
            return nameCount == 0 ? Paths.get("") : reference.subpath(0, nameCount);
        }
        Path result = root;
        for (int i = 0; i < nameCount; i++) {
            result = result.resolve(reference.getName(i));
        }
        return result;
    }

    private static boolean sameRoot(Path left, Path right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.toString().equalsIgnoreCase(right.toString());
    }

    private static boolean segmentEquals(String left, String right) {
        return isWindows() ? left.equalsIgnoreCase(right) : left.equals(right);
    }
}
