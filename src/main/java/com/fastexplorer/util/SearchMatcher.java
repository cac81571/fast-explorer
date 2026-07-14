package com.fastexplorer.util;

import com.fastexplorer.model.SearchOptions;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

public final class SearchMatcher {

    private SearchMatcher() {}

    public static boolean matches(
            Path root,
            Path entryPath,
            String name,
            boolean isDirectory,
            SearchOptions options
    ) {
        if (options.isEmpty()) {
            return true;
        }
        if (options.directoriesOnly() && !isDirectory) {
            return false;
        }
        if (!options.pathPatterns().isEmpty()) {
            String relativePath = relativizePath(root, entryPath);
            if (!matchesAnyGlob(relativePath, options.pathPatterns())) {
                return false;
            }
        }
        if (!options.fileNamePatterns().isEmpty()) {
            if (!matchesAnyGlob(name, options.fileNamePatterns())) {
                return false;
            }
        }
        if (options.directoriesOnly()) {
            // フォルダのみ: 拡張子条件は適用しない
            return true;
        }
        if (!options.extensions().isEmpty()) {
            if (isDirectory) {
                return options.extensions().stream().anyMatch(SearchMatcher::isFolderToken);
            }
            return matchesExtension(name, options.extensions());
        }
        return true;
    }

    private static String relativizePath(Path root, Path entryPath) {
        try {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path normalizedEntry = entryPath.toAbsolutePath().normalize();
            if (normalizedEntry.equals(normalizedRoot)) {
                return "";
            }
            return normalizedRoot.relativize(normalizedEntry).toString().replace('\\', '/');
        } catch (IllegalArgumentException ex) {
            return entryPath.toString().replace('\\', '/');
        }
    }

    private static boolean matchesAnyGlob(String text, List<String> globs) {
        String normalizedText = text.replace('\\', '/');
        for (String glob : globs) {
            if (!containsWildcard(glob)) {
                if (normalizedText.toLowerCase().contains(glob.replace('\\', '/').toLowerCase())) {
                    return true;
                }
                continue;
            }
            Pattern pattern = WildcardUtil.globToPattern(glob.replace('\\', '/'));
            if (WildcardUtil.matches(normalizedText, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWildcard(String value) {
        return value.indexOf('*') >= 0 || value.indexOf('?') >= 0;
    }

    private static boolean matchesExtension(String fileName, List<String> extensions) {
        String ext = FileTypeUtil.extensionOf(fileName);
        for (String entry : extensions) {
            if (isFolderToken(entry)) {
                continue;
            }
            String normalized = entry.startsWith(".") ? entry.toLowerCase() : "." + entry.toLowerCase();
            if (normalized.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFolderToken(String entry) {
        return "フォルダ".equalsIgnoreCase(entry) || "folder".equalsIgnoreCase(entry);
    }
}
