package com.fastexplorer.model;

import java.util.List;

public record SearchOptions(
        List<String> pathPatterns,
        List<String> fileNamePatterns,
        List<String> extensions,
        boolean directoriesOnly
) {
    public SearchOptions {
        pathPatterns = pathPatterns != null ? List.copyOf(pathPatterns) : List.of();
        fileNamePatterns = fileNamePatterns != null ? List.copyOf(fileNamePatterns) : List.of();
        extensions = extensions != null ? List.copyOf(extensions) : List.of();
    }

    public boolean isEmpty() {
        return pathPatterns.isEmpty() && fileNamePatterns.isEmpty() && extensions.isEmpty();
    }

    public boolean isSimpleNameQuery() {
        return pathPatterns.isEmpty()
                && extensions.isEmpty()
                && !directoriesOnly
                && fileNamePatterns.size() == 1
                && !containsWildcard(fileNamePatterns.get(0));
    }

    public String simpleNameQuery() {
        return fileNamePatterns.isEmpty() ? "" : fileNamePatterns.get(0).trim();
    }

    private static boolean containsWildcard(String value) {
        return value.indexOf('*') >= 0 || value.indexOf('?') >= 0;
    }
}
