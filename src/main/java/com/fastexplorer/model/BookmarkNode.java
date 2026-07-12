package com.fastexplorer.model;

import java.util.ArrayList;
import java.util.List;

public final class BookmarkNode {

    public static final String CATEGORY_PATH = "パス";
    public static final String CATEGORY_SEARCH = "検索条件";
    public static final String CATEGORY_GREP = "Grep";

    public enum Kind {
        CATEGORY,
        FOLDER,
        PATH,
        SEARCH,
        GREP
    }

    private final Kind kind;
    private String name;
    private String path;
    private String searchPathPatterns = "";
    private String searchFilePatterns = "";
    private String searchExtensions = "";
    private String grepPattern = "";
    private String grepPath = "";
    private String grepFile = "";
    private String grepExtension = "";
    private boolean grepRegex;
    private boolean grepRecursive = true;
    private int grepContext;
    private final List<BookmarkNode> children = new ArrayList<>();

    private BookmarkNode(Kind kind, String name) {
        this.kind = kind;
        this.name = name;
    }

    public static BookmarkNode category(String name) {
        return new BookmarkNode(Kind.CATEGORY, name);
    }

    public static BookmarkNode folder(String name) {
        return new BookmarkNode(Kind.FOLDER, name);
    }

    public static BookmarkNode path(String path, String name) {
        BookmarkNode node = new BookmarkNode(Kind.PATH, name);
        node.path = path;
        return node;
    }

    public static BookmarkNode search(
            String name,
            String pathPatterns,
            String filePatterns,
            String extensions
    ) {
        BookmarkNode node = new BookmarkNode(Kind.SEARCH, name);
        node.searchPathPatterns = nullToEmpty(pathPatterns);
        node.searchFilePatterns = nullToEmpty(filePatterns);
        node.searchExtensions = nullToEmpty(extensions);
        return node;
    }

    public static BookmarkNode grep(
            String name,
            String pattern,
            String path,
            String file,
            String extension,
            boolean regex,
            boolean recursive,
            int context
    ) {
        BookmarkNode node = new BookmarkNode(Kind.GREP, name);
        node.grepPattern = nullToEmpty(pattern);
        node.grepPath = nullToEmpty(path);
        node.grepFile = nullToEmpty(file);
        node.grepExtension = nullToEmpty(extension);
        node.grepRegex = regex;
        node.grepRecursive = recursive;
        node.grepContext = Math.max(0, context);
        return node;
    }

    public Kind kind() {
        return kind;
    }

    public boolean isFolder() {
        return kind == Kind.FOLDER || kind == Kind.CATEGORY;
    }

    public boolean isSystemCategory() {
        return kind == Kind.CATEGORY;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String path() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String searchPathPatterns() {
        return searchPathPatterns;
    }

    public String searchFilePatterns() {
        return searchFilePatterns;
    }

    public String searchExtensions() {
        return searchExtensions;
    }

    public String grepPattern() {
        return grepPattern;
    }

    public String grepPath() {
        return grepPath;
    }

    public String grepFile() {
        return grepFile;
    }

    public String grepExtension() {
        return grepExtension;
    }

    public boolean grepRegex() {
        return grepRegex;
    }

    public boolean grepRecursive() {
        return grepRecursive;
    }

    public int grepContext() {
        return grepContext;
    }

    public List<BookmarkNode> children() {
        return children;
    }

    public String displayText() {
        if (isFolder()) {
            return name;
        }
        if (name != null && !name.isBlank()) {
            return name;
        }
        return switch (kind) {
            case PATH -> path != null ? path : "";
            case SEARCH -> summarizeSearch();
            case GREP -> summarizeGrep();
            default -> "";
        };
    }

    public String detailText() {
        return switch (kind) {
            case PATH -> path != null ? path : "";
            case SEARCH -> summarizeSearch();
            case GREP -> summarizeGrep();
            default -> "";
        };
    }

    private String summarizeSearch() {
        List<String> parts = new ArrayList<>();
        if (!searchPathPatterns.isBlank()) {
            parts.add("パス=" + searchPathPatterns);
        }
        if (!searchFilePatterns.isBlank()) {
            parts.add("ファイル=" + searchFilePatterns);
        }
        if (!searchExtensions.isBlank()) {
            parts.add("拡張子=" + searchExtensions);
        }
        return String.join(" ", parts);
    }

    private String summarizeGrep() {
        List<String> parts = new ArrayList<>();
        if (!grepPattern.isBlank()) {
            parts.add("\"" + grepPattern + "\"");
        }
        if (!grepPath.isBlank()) {
            parts.add("パス=" + grepPath);
        }
        if (!grepFile.isBlank()) {
            parts.add("ファイル=" + grepFile);
        }
        if (!grepExtension.isBlank()) {
            parts.add("拡張子=" + grepExtension);
        }
        if (grepRegex) {
            parts.add("正規表現");
        }
        if (!grepRecursive) {
            parts.add("直下のみ");
        }
        if (grepContext > 0) {
            parts.add("前後" + grepContext);
        }
        return String.join(" ", parts);
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
