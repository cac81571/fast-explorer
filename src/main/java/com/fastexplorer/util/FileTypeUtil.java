package com.fastexplorer.util;

import com.fastexplorer.model.FileEntry;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

public final class FileTypeUtil {

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".txt", ".md", ".java", ".xml", ".json", ".properties", ".csv", ".log",
            ".yml", ".yaml", ".html", ".htm", ".css", ".js", ".ts", ".jsx", ".tsx",
            ".sql", ".bat", ".ps1", ".sh", ".ini", ".cfg", ".conf", ".gradle", ".toml",
            ".kt", ".kts", ".py", ".rb", ".php", ".c", ".cpp", ".h", ".hpp", ".cs",
            ".vb", ".env", ".gitignore", ".editorconfig"
    );

    private FileTypeUtil() {}

    public static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot).toLowerCase();
    }

    public static String extensionLabel(String filename) {
        String ext = extensionOf(filename);
        return ext.isEmpty() ? "(なし)" : ext;
    }

    public static String typeDisplay(FileEntry entry) {
        if (entry.directory()) {
            return "フォルダ";
        }
        return extensionLabel(entry.name());
    }

    public static boolean isTextFile(String fileName) {
        String ext = extensionOf(fileName);
        if (TEXT_EXTENSIONS.contains(ext)) {
            return true;
        }
        String lower = fileName.toLowerCase();
        return lower.equals("readme") || lower.equals("license") || lower.equals("makefile");
    }

    public static boolean isTextFile(Path path) {
        return path.getFileName() != null && isTextFile(path.getFileName().toString());
    }

    public static boolean isBinaryFile(FileEntry entry) {
        return !entry.directory() && !isTextFile(entry.name());
    }

    /** フィルタ入力を `.pdf` 形式に正規化。null = フィルタなし */
    public static String normalizeFilter(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty() || "(すべて)".equals(trimmed)) {
            return null;
        }
        if ("(なし)".equals(trimmed) || "なし".equals(trimmed)) {
            return "(なし)";
        }
        if ("フォルダ".equals(trimmed)) {
            return "フォルダ";
        }
        String lower = trimmed.toLowerCase();
        if (!lower.startsWith(".")) {
            lower = "." + lower;
        }
        return lower;
    }

    /** カンマ/セミコロン/空白区切りの種類フィルタを解析。空 = フィルタなし */
    public static Set<String> parseTypeFilters(String input) {
        if (input == null || input.isBlank()) {
            return Set.of();
        }
        Set<String> filters = new LinkedHashSet<>();
        for (String token : input.split("[,;\\s]+")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String normalized = normalizeFilter(trimmed);
            if (normalized != null) {
                filters.add(normalized);
            }
        }
        return filters;
    }
}
