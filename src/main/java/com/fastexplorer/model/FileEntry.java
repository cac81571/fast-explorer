package com.fastexplorer.model;

import java.nio.file.Path;
import java.time.Instant;

public record FileEntry(
        String name,
        Path path,
        boolean directory,
        Long size,
        Instant modified
) {}
