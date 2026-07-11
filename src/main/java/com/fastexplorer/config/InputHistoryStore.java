package com.fastexplorer.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class InputHistoryStore {

    public static final int MAX_ENTRIES = 20;

    private static final Path STORE_DIR = Path.of(System.getProperty("user.home"), ".fast-explorer");
    private static final Path STORE_FILE = STORE_DIR.resolve("input-history.txt");

    private InputHistoryStore() {}

    public static List<String> load(String key) {
        return loadAll().getOrDefault(key, List.of());
    }

    public static Map<String, List<String>> loadAll() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try {
            if (!Files.isRegularFile(STORE_FILE)) {
                return result;
            }
            String currentKey = null;
            for (String line : Files.readAllLines(STORE_FILE, StandardCharsets.UTF_8)) {
                if (line.startsWith("@")) {
                    currentKey = line.substring(1).trim();
                    if (!currentKey.isEmpty()) {
                        result.putIfAbsent(currentKey, new ArrayList<>());
                    }
                    continue;
                }
                if (currentKey == null) {
                    continue;
                }
                String value = line.trim();
                if (!value.isEmpty()) {
                    result.get(currentKey).add(value);
                }
            }
            for (Map.Entry<String, List<String>> entry : result.entrySet()) {
                entry.setValue(normalize(entry.getValue()));
            }
        } catch (IOException ignored) {
        }
        return result;
    }

    public static void save(String key, List<String> history) {
        if (key == null || key.isBlank()) {
            return;
        }
        Map<String, List<String>> all = loadAll();
        all.put(key, normalize(history));
        persistAll(all);
    }

    private static void persistAll(Map<String, List<String>> all) {
        try {
            Files.createDirectories(STORE_DIR);
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : all.entrySet()) {
                List<String> history = entry.getValue();
                if (history.isEmpty()) {
                    continue;
                }
                lines.add("@" + entry.getKey());
                lines.addAll(history);
                lines.add("");
            }
            Files.write(STORE_FILE, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static List<String> normalize(List<String> history) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (history != null) {
            for (String entry : history) {
                if (entry != null) {
                    String trimmed = entry.trim();
                    if (!trimmed.isEmpty()) {
                        unique.add(trimmed);
                    }
                }
            }
        }
        List<String> normalized = new ArrayList<>(unique);
        if (normalized.size() > MAX_ENTRIES) {
            return normalized.subList(0, MAX_ENTRIES);
        }
        return normalized;
    }
}
