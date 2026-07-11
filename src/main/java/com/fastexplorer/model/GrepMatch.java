package com.fastexplorer.model;

import java.nio.file.Path;

public record GrepMatch(
        Path path,
        int lineNumber,
        String line
) {}
