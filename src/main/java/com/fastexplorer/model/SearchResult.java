package com.fastexplorer.model;

import java.nio.file.Path;
import java.util.List;

public record SearchResult(
        Path root,
        List<FileEntry> entries,
        long elapsedMs,
        boolean fromCache
) {
    public SearchResult(Path root, List<FileEntry> entries, long elapsedMs) {
        this(root, entries, elapsedMs, false);
    }
}
