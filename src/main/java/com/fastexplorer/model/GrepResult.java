package com.fastexplorer.model;

import java.nio.file.Path;
import java.util.List;

public record GrepResult(
        Path root,
        List<GrepMatch> matches,
        long elapsedMs,
        int filesScanned
) {}
