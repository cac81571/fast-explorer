package com.fastexplorer.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public final class LocalFileOpener {

    private static final Path TEMP_ROOT = Paths.get(System.getProperty("java.io.tmpdir"), "fast-explorer");

    private LocalFileOpener() {}

    public static Path copyToLocalTemp(Path source) throws IOException {
        Path resolved = PathUtil.resolveForAccess(source);
        Path dest = mirrorPathUnderTemp(resolved);
        Path parent = dest.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(resolved, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        return dest;
    }

    private static Path mirrorPathUnderTemp(Path path) {
        String pathText = path.toAbsolutePath().normalize().toString();
        return TEMP_ROOT.resolve(Paths.get(pathText.substring(2)));
    }
}
