package com.fastexplorer.fs;

import com.fastexplorer.model.GrepMatch;
import com.fastexplorer.model.GrepOptions;
import com.fastexplorer.model.GrepResult;
import com.fastexplorer.util.FileTypeUtil;
import com.fastexplorer.util.PathUtil;
import com.fastexplorer.util.WildcardUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class TextGrepService {

    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024L;
    private static final int MAX_MATCHES = 10_000;
    private static final int MAX_LINE_DISPLAY = 300;

    public GrepResult grep(
            GrepOptions options,
            String patternText,
            boolean regex,
            boolean caseSensitive,
            boolean recursive,
            AtomicBoolean cancel
    ) throws IOException {
        long start = System.nanoTime();
        Path root = PathUtil.resolveForAccess(options.searchRoot());
        Pattern linePattern = compilePattern(patternText, regex, caseSensitive);
        Pattern fileNamePattern = WildcardUtil.globToPattern(options.fileNamePattern());

        List<GrepMatch> matches = new ArrayList<>();
        int[] filesScanned = {0};

        if (recursive) {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (cancel.get() || matches.size() >= MAX_MATCHES) {
                        return FileVisitResult.TERMINATE;
                    }
                    scanFile(file, attrs, options, fileNamePattern, linePattern, matches, filesScanned, cancel);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                for (Path entry : stream) {
                    if (cancel.get() || matches.size() >= MAX_MATCHES) {
                        break;
                    }
                    if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(
                                    entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                            scanFile(entry, attrs, options, fileNamePattern, linePattern, matches, filesScanned, cancel);
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        return new GrepResult(root, List.copyOf(matches), elapsedMs, filesScanned[0]);
    }

    public GrepResult grepPaths(
            Collection<Path> paths,
            GrepOptions options,
            String patternText,
            boolean regex,
            boolean caseSensitive,
            AtomicBoolean cancel
    ) throws IOException {
        long start = System.nanoTime();
        Pattern linePattern = compilePattern(patternText, regex, caseSensitive);
        Pattern fileNamePattern = WildcardUtil.globToPattern(options.fileNamePattern());
        List<GrepMatch> matches = new ArrayList<>();
        int[] filesScanned = {0};

        for (Path path : paths) {
            if (cancel.get() || matches.size() >= MAX_MATCHES) {
                break;
            }
            try {
                Path file = PathUtil.resolveForAccess(path);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                BasicFileAttributes attrs = Files.readAttributes(
                        file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                scanFile(file, attrs, options, fileNamePattern, linePattern, matches, filesScanned, cancel);
            } catch (IOException ignored) {
                // 個別ファイルの失敗でファイル集合全体の Grep を中断しない
            }
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        return new GrepResult(options.searchRoot(), List.copyOf(matches), elapsedMs, filesScanned[0]);
    }

    private static void scanFile(
            Path file,
            BasicFileAttributes attrs,
            GrepOptions options,
            Pattern fileNamePattern,
            Pattern linePattern,
            List<GrepMatch> matches,
            int[] filesScanned,
            AtomicBoolean cancel
    ) {
        String fileName = file.getFileName().toString();
        if (!WildcardUtil.matches(fileName, fileNamePattern)) {
            return;
        }
        if (!matchesExtension(file, options.extensions())) {
            return;
        }
        if (attrs.size() > MAX_FILE_BYTES) {
            return;
        }
        filesScanned[0]++;
        grepFile(file, linePattern, matches, cancel);
    }

    static boolean matchesExtension(Path file, List<String> extensions) {
        if (extensions.isEmpty()) {
            return FileTypeUtil.isTextFile(file);
        }
        String ext = FileTypeUtil.extensionOf(file.getFileName().toString());
        for (String entry : extensions) {
            String normalized = entry.startsWith(".") ? entry.toLowerCase() : "." + entry.toLowerCase();
            if (normalized.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    private static void grepFile(
            Path file,
            Pattern pattern,
            List<GrepMatch> matches,
            AtomicBoolean cancel
    ) {
        for (Charset charset : charsetsToTry()) {
            try {
                grepFileWithCharset(file, pattern, matches, cancel, charset);
                return;
            } catch (MalformedInputException ignored) {
            } catch (IOException ignored) {
                return;
            }
        }
    }

    private static List<Charset> charsetsToTry() {
        List<Charset> charsets = new ArrayList<>();
        charsets.add(StandardCharsets.UTF_8);
        Charset defaultCharset = Charset.defaultCharset();
        if (!StandardCharsets.UTF_8.equals(defaultCharset)) {
            charsets.add(defaultCharset);
        }
        charsets.add(StandardCharsets.ISO_8859_1);
        return charsets;
    }

    private static void grepFileWithCharset(
            Path file,
            Pattern pattern,
            List<GrepMatch> matches,
            AtomicBoolean cancel,
            Charset charset
    ) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, charset)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                if (cancel.get() || matches.size() >= MAX_MATCHES) {
                    return;
                }
                lineNumber++;
                if (pattern.matcher(line).find()) {
                    matches.add(new GrepMatch(file, lineNumber, truncateLine(line)));
                }
            }
        }
    }

    private static String truncateLine(String line) {
        if (line.length() <= MAX_LINE_DISPLAY) {
            return line;
        }
        return line.substring(0, MAX_LINE_DISPLAY) + "…";
    }

    static Pattern compilePattern(String patternText, boolean regex, boolean caseSensitive)
            throws PatternSyntaxException {
        String trimmed = patternText.trim();
        if (trimmed.isEmpty()) {
            throw new PatternSyntaxException("empty pattern", trimmed, -1);
        }
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        if (regex) {
            return Pattern.compile(trimmed, flags);
        }
        return Pattern.compile(Pattern.quote(trimmed), flags);
    }
}
