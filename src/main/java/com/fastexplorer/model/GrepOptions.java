package com.fastexplorer.model;

import java.nio.file.Path;
import java.util.List;

public record GrepOptions(
        Path searchRoot,
        String fileNamePattern,
        List<String> extensions
) {
    public GrepOptions {
        extensions = extensions != null ? List.copyOf(extensions) : List.of();
        fileNamePattern = fileNamePattern != null ? fileNamePattern.trim() : "";
    }
}
