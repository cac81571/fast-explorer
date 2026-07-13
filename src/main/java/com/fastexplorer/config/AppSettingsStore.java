package com.fastexplorer.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

public final class AppSettingsStore {

    private static final Path STORE_DIR = Path.of(System.getProperty("user.home"), ".fast-explorer");
    private static final Path STORE_FILE = STORE_DIR.resolve("settings.properties");
    private static final String KEY_FETCH_METADATA = "fetchMetadata";

    private AppSettingsStore() {}

    public record Settings(boolean fetchMetadata) {
        public static Settings defaults() {
            return new Settings(true);
        }
    }

    public static Settings load() {
        try {
            if (!Files.isRegularFile(STORE_FILE)) {
                return Settings.defaults();
            }
            Properties properties = new Properties();
            try (var in = Files.newInputStream(STORE_FILE)) {
                properties.load(in);
            }
            String value = properties.getProperty(KEY_FETCH_METADATA, "true");
            return new Settings(Boolean.parseBoolean(value.trim()));
        } catch (IOException ignored) {
            return Settings.defaults();
        }
    }

    public static void save(Settings settings) {
        try {
            Files.createDirectories(STORE_DIR);
            Properties properties = new Properties();
            properties.setProperty(KEY_FETCH_METADATA, Boolean.toString(settings.fetchMetadata()));
            List<String> lines = properties.stringPropertyNames().stream()
                    .sorted()
                    .map(key -> key + "=" + properties.getProperty(key))
                    .toList();
            Files.write(STORE_FILE, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
