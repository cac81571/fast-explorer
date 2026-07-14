package com.fastexplorer.config;

import com.fastexplorer.model.BookmarkNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class BookmarkStore {

    private static final Path STORE_DIR = Path.of(System.getProperty("user.home"), ".fast-explorer");
    private static final Path STORE_FILE = STORE_DIR.resolve("bookmarks.txt");

    private BookmarkStore() {}

    public static List<BookmarkNode> load() {
        List<BookmarkNode> roots = new ArrayList<>();
        try {
            if (!Files.isRegularFile(STORE_FILE)) {
                return defaultCategories();
            }
            Deque<BookmarkNode> stack = new ArrayDeque<>();
            for (String rawLine : Files.readAllLines(STORE_FILE, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if ("E".equals(line)) {
                    if (!stack.isEmpty()) {
                        stack.pop();
                    }
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length < 2) {
                    continue;
                }
                String type = parts[0].trim();
                switch (type) {
                    case "C" -> {
                        BookmarkNode category = BookmarkNode.category(parts[1].trim());
                        appendChild(stack, roots, category);
                        stack.push(category);
                    }
                    case "F" -> {
                        BookmarkNode folder = BookmarkNode.folder(parts[1].trim());
                        appendChild(stack, roots, folder);
                        stack.push(folder);
                    }
                    case "P" -> appendChild(stack, roots, BookmarkNode.path(
                            parts[1].trim(),
                            parts.length >= 3 ? parts[2].trim() : ""
                    ));
                    case "S" -> appendChild(stack, roots, BookmarkNode.search(
                            field(parts, 1),
                            field(parts, 2),
                            field(parts, 3),
                            field(parts, 4),
                            "1".equals(field(parts, 5))
                    ));
                    case "G" -> appendChild(stack, roots, BookmarkNode.grep(
                            field(parts, 1),
                            field(parts, 2),
                            field(parts, 3),
                            field(parts, 4),
                            field(parts, 5),
                            "1".equals(field(parts, 6)),
                            !"0".equals(field(parts, 7)),
                            parseInt(field(parts, 8), 0)
                    ));
                    default -> {
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return normalizeRoots(roots);
    }

    public static void save(List<BookmarkNode> roots) {
        try {
            Files.createDirectories(STORE_DIR);
            List<String> lines = new ArrayList<>();
            List<BookmarkNode> normalized = normalizeRoots(roots);
            for (BookmarkNode node : normalized) {
                writeNode(lines, node);
            }
            Files.write(STORE_FILE, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static List<BookmarkNode> normalizeRoots(List<BookmarkNode> roots) {
        if (roots == null || roots.isEmpty()) {
            return defaultCategories();
        }
        if (!hasCategories(roots)) {
            BookmarkNode pathCategory = BookmarkNode.category(BookmarkNode.CATEGORY_PATH);
            pathCategory.children().addAll(roots);
            List<BookmarkNode> migrated = new ArrayList<>();
            migrated.add(pathCategory);
            migrated.add(BookmarkNode.category(BookmarkNode.CATEGORY_SEARCH));
            migrated.add(BookmarkNode.category(BookmarkNode.CATEGORY_GREP));
            return migrated;
        }
        List<BookmarkNode> normalized = new ArrayList<>(3);
        normalized.add(firstOrCreateCategory(roots, BookmarkNode.CATEGORY_PATH));
        normalized.add(firstOrCreateCategory(roots, BookmarkNode.CATEGORY_SEARCH));
        normalized.add(firstOrCreateCategory(roots, BookmarkNode.CATEGORY_GREP));
        return normalized;
    }

    private static BookmarkNode firstOrCreateCategory(List<BookmarkNode> roots, String name) {
        BookmarkNode found = findCategory(roots, name);
        return found != null ? found : BookmarkNode.category(name);
    }

    private static boolean hasCategories(List<BookmarkNode> roots) {
        for (BookmarkNode node : roots) {
            if (node.isSystemCategory()) {
                return true;
            }
        }
        return false;
    }

    private static BookmarkNode findCategory(List<BookmarkNode> roots, String name) {
        for (BookmarkNode node : roots) {
            if (node.isSystemCategory() && name.equals(node.name())) {
                return node;
            }
        }
        return null;
    }

    private static List<BookmarkNode> defaultCategories() {
        return List.of(
                BookmarkNode.category(BookmarkNode.CATEGORY_PATH),
                BookmarkNode.category(BookmarkNode.CATEGORY_SEARCH),
                BookmarkNode.category(BookmarkNode.CATEGORY_GREP)
        );
    }

    private static void appendChild(Deque<BookmarkNode> stack, List<BookmarkNode> roots, BookmarkNode node) {
        if (stack.isEmpty()) {
            roots.add(node);
        } else {
            stack.peek().children().add(node);
        }
    }

    private static void writeNode(List<String> lines, BookmarkNode node) {
        switch (node.kind()) {
            case CATEGORY -> {
                lines.add("C\t" + nullToEmpty(node.name()));
                for (BookmarkNode child : node.children()) {
                    writeNode(lines, child);
                }
                lines.add("E");
            }
            case FOLDER -> {
                lines.add("F\t" + nullToEmpty(node.name()));
                for (BookmarkNode child : node.children()) {
                    writeNode(lines, child);
                }
                lines.add("E");
            }
            case PATH -> lines.add("P\t" + nullToEmpty(node.path()) + "\t" + nullToEmpty(node.name()));
            case SEARCH -> lines.add("S\t"
                    + nullToEmpty(node.name()) + "\t"
                    + nullToEmpty(node.searchPathPatterns()) + "\t"
                    + nullToEmpty(node.searchFilePatterns()) + "\t"
                    + nullToEmpty(node.searchExtensions()) + "\t"
                    + (node.searchDirectoriesOnly() ? "1" : "0"));
            case GREP -> lines.add("G\t"
                    + nullToEmpty(node.name()) + "\t"
                    + nullToEmpty(node.grepPattern()) + "\t"
                    + nullToEmpty(node.grepPath()) + "\t"
                    + nullToEmpty(node.grepFile()) + "\t"
                    + nullToEmpty(node.grepExtension()) + "\t"
                    + (node.grepRegex() ? "1" : "0") + "\t"
                    + (node.grepRecursive() ? "1" : "0") + "\t"
                    + node.grepContext());
            default -> {
            }
        }
    }

    private static String field(String[] parts, int index) {
        return index < parts.length ? parts[index].trim() : "";
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
