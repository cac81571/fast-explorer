package com.fastexplorer.model;

import java.nio.file.Path;
import java.util.List;

public record ListDirectoryResult(
        Path path,
        List<FileEntry> entries,
        long elapsedMs,
        boolean fromCache,
        boolean backgroundRefresh
) {
    public ListDirectoryResult(Path path, List<FileEntry> entries, long elapsedMs) {
        this(path, entries, elapsedMs, false, false);
    }
}
