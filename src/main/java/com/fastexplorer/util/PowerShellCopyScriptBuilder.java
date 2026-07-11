package com.fastexplorer.util;

import com.fastexplorer.model.FileEntry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PowerShellCopyScriptBuilder {

    private PowerShellCopyScriptBuilder() {}

    public static String build(List<FileEntry> entries, Path destDir, Path hierarchyBase) {
        StringBuilder script = new StringBuilder();
        script.append("# Fast Explorer - Copy-Item script").append('\n');
        script.append("$ErrorActionPreference = 'Stop'").append('\n');
        script.append('\n');

        Path normalizedDestDir = destDir.toAbsolutePath().normalize();
        Map<String, CopyCommand> copyCommands = new LinkedHashMap<>();
        Set<Path> directories = new LinkedHashSet<>();
        directories.add(normalizedDestDir);

        for (FileEntry entry : entries) {
            if (entry.directory()) {
                continue;
            }
            Path source = entry.path();
            try {
                Path dest = PathUtil.resolveHierarchyCopyDestination(normalizedDestDir, source, hierarchyBase);
                copyCommands.putIfAbsent(PathUtil.toDisplay(dest), new CopyCommand(source, dest));
                collectParentDirectories(directories, normalizedDestDir, dest.getParent());
            } catch (IOException ex) {
                script.append("# ERROR: ")
                        .append(PathUtil.toDisplay(source))
                        .append(" - ")
                        .append(ex.getMessage())
                        .append('\n');
            }
        }

        List<Path> sortedDirectories = new ArrayList<>(directories);
        sortedDirectories.sort(Comparator.comparingInt(Path::getNameCount));

        for (Path directory : sortedDirectories) {
            script.append("New-Item -ItemType Directory -Force -Path ")
                    .append(quoteLiteral(PathUtil.toDisplay(directory)))
                    .append(" | Out-Null")
                    .append('\n');
        }
        if (!sortedDirectories.isEmpty()) {
            script.append('\n');
        }

        for (CopyCommand command : copyCommands.values()) {
            script.append("Copy-Item -LiteralPath ")
                    .append(quoteLiteral(PathUtil.toDisplay(command.source())))
                    .append(" -Destination ")
                    .append(quoteLiteral(PathUtil.toDisplay(command.dest())))
                    .append(" -Force")
                    .append('\n');
        }

        script.append('\n');
        script.append("# Files: ").append(copyCommands.size()).append('\n');
        return script.toString();
    }

    private static void collectParentDirectories(Set<Path> directories, Path destRoot, Path directory) {
        Path current = directory;
        while (current != null && isSameOrUnder(current, destRoot)) {
            directories.add(current);
            if (current.equals(destRoot)) {
                break;
            }
            current = current.getParent();
        }
    }

    private static boolean isSameOrUnder(Path path, Path root) {
        Path normalizedPath = path.toAbsolutePath().normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        return normalizedPath.equals(normalizedRoot) || normalizedPath.startsWith(normalizedRoot);
    }

    private static String quoteLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private record CopyCommand(Path source, Path dest) {}
}
