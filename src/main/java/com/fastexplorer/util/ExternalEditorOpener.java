package com.fastexplorer.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ExternalEditorOpener {

    private ExternalEditorOpener() {}

    public static void openAtLine(String commandTemplate, Path file, int lineNumber) throws IOException {
        if (commandTemplate == null || commandTemplate.isBlank()) {
            throw new IOException("エディタコマンドが未設定です");
        }
        if (lineNumber < 1) {
            throw new IOException("行番号が不正です: " + lineNumber);
        }

        String filePath = PathUtil.toDisplay(file.toAbsolutePath().normalize());
        String expanded = expandTemplate(commandTemplate.trim(), filePath, lineNumber);
        List<String> command = parseCommandLine(expanded);
        if (command.isEmpty()) {
            throw new IOException("エディタコマンドが空です");
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(file.getParent() != null ? file.getParent().toFile() : null);
        builder.start();
    }

    private static String expandTemplate(String template, String filePath, int lineNumber) {
        String fileLine = filePath + ":" + lineNumber;
        String fileLineColumn = filePath + ":" + lineNumber + ":1";
        return template
                .replace("{file}:{line}:{column}", quoteIfNeeded(fileLineColumn))
                .replace("{file}:{line}", quoteIfNeeded(fileLine))
                .replace("{file}", quoteIfNeeded(filePath))
                .replace("{line}", Integer.toString(lineNumber))
                .replace("{column}", "1");
    }

    private static String quoteIfNeeded(String value) {
        if (value.indexOf(' ') >= 0 && !(value.startsWith("\"") && value.endsWith("\""))) {
            return "\"" + value + "\"";
        }
        return value;
    }

    static List<String> parseCommandLine(String commandLine) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }
}
