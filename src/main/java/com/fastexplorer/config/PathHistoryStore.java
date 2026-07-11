package com.fastexplorer.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class PathHistoryStore {

    public static final int MAX_ENTRIES = 50;

    private static final Path STORE_DIR = Path.of(System.getProperty("user.home"), ".fast-explorer");
    private static final Path STORE_FILE = STORE_DIR.resolve("path-history.txt");

    private PathHistoryStore() {}

    public record State(String lastPath, List<String> history) {
        public State {
            lastPath = lastPath != null ? lastPath : "";
            history = history != null ? List.copyOf(history) : List.of();
        }
    }

    public static State load() {
        try {
            if (!Files.isRegularFile(STORE_FILE)) {
                return new State("", List.of());
            }

            String lastPath = "";
            List<String> history = new ArrayList<>();
            for (String rawLine : Files.readAllLines(STORE_FILE, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("last:")) {
                    lastPath = line.substring("last:".length()).trim();
                } else {
                    history.add(line);
                }
            }

            LinkedHashSet<String> unique = new LinkedHashSet<>();
            if (!lastPath.isBlank()) {
                unique.add(lastPath);
            }
            unique.addAll(history);

            List<String> normalized = new ArrayList<>(unique);
            if (normalized.size() > MAX_ENTRIES) {
                normalized = normalized.subList(0, MAX_ENTRIES);
            }
            return new State(lastPath, normalized);
        } catch (IOException e) {
            return new State("", List.of());
        }
    }

    public static void save(String lastPath, List<String> history) {
        try {
            Files.createDirectories(STORE_DIR);

            LinkedHashSet<String> unique = new LinkedHashSet<>();
            String trimmedLast = lastPath != null ? lastPath.trim() : "";
            if (!trimmedLast.isBlank()) {
                unique.add(trimmedLast);
            }
            if (history != null) {
                for (String entry : history) {
                    if (entry != null && !entry.isBlank()) {
                        unique.add(entry.trim());
                    }
                }
            }

            List<String> lines = new ArrayList<>();
            lines.add("last:" + trimmedLast);
            int count = 0;
            for (String entry : unique) {
                if (count >= MAX_ENTRIES) {
                    break;
                }
                lines.add(entry);
                count++;
            }
            Files.write(STORE_FILE, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 履歴保存失敗はアプリ動作に影響させない
        }
    }
}
