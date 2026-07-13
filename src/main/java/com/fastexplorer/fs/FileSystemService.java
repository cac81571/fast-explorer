package com.fastexplorer.fs;

import com.fastexplorer.model.FileEntry;
import com.fastexplorer.model.ListDirectoryResult;
import com.fastexplorer.model.SearchOptions;
import com.fastexplorer.model.SearchResult;
import com.fastexplorer.util.PathUtil;
import com.fastexplorer.util.SearchMatcher;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Windows エクスプローラが遅い主な原因を避ける:
 * - シェル拡張 / サムネイル / プロパティハンドラを使わない
 * - DirectoryStream で名前とディレクトリ判定のみ（最速モード）
 * - サイズ・更新日時は readAttributes で取得（フォルダは更新日時のみ）
 */
public final class FileSystemService {

    public ListDirectoryResult listDirectory(Path dirPath, boolean includeSize) throws IOException {
        long start = System.nanoTime();
        Path accessPath = PathUtil.resolveForAccess(dirPath);

        List<FileEntry> entries = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(accessPath)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                boolean isDirectory;
                Long size = null;
                Instant modified = null;

                if (includeSize) {
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(
                                entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        isDirectory = attrs.isDirectory();
                        modified = attrs.lastModifiedTime().toInstant();
                        if (!isDirectory) {
                            size = attrs.size();
                        }
                    } catch (IOException ignored) {
                        isDirectory = Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
                    }
                } else {
                    isDirectory = Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
                }

                entries.add(new FileEntry(name, entry, isDirectory, size, modified));
            }
        } catch (NotDirectoryException e) {
            throw new NotDirectoryException(accessPath.toString());
        } catch (NoSuchFileException e) {
            throw new NoSuchFileException(accessPath.toString());
        }

        entries.sort(Comparator
                .comparing(FileEntry::directory).reversed()
                .thenComparing(e -> e.name().toLowerCase()));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        return new ListDirectoryResult(accessPath, entries, elapsedMs);
    }

    public Path getParent(Path path) {
        Path parent = path.getParent();
        if (parent == null) {
            return path;
        }
        return parent;
    }

    public FileEntry toFileEntry(Path path) {
        return toFileEntry(path, true);
    }

    public FileEntry toFileEntry(Path path, boolean includeMetadata) {
        Path normalized = path.toAbsolutePath().normalize();
        String name = normalized.getFileName() != null
                ? normalized.getFileName().toString()
                : normalized.toString();
        try {
            Path access = PathUtil.resolveForAccess(normalized);
            if (includeMetadata) {
                BasicFileAttributes attrs = Files.readAttributes(
                        access, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                boolean isDirectory = attrs.isDirectory();
                Long size = isDirectory ? null : attrs.size();
                Instant modified = attrs.lastModifiedTime().toInstant();
                return new FileEntry(name, normalized, isDirectory, size, modified);
            }
            boolean isDirectory = Files.isDirectory(access, LinkOption.NOFOLLOW_LINKS);
            return new FileEntry(name, normalized, isDirectory, null, null);
        } catch (IOException ex) {
            boolean isDirectory = Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS);
            return new FileEntry(name, normalized, isDirectory, null, null);
        }
    }

    /**
     * 指定フォルダ以下を再帰的に検索（パス / ファイル名 glob、拡張子。各項目は OR、項目間は AND）。
     */
    public SearchResult searchRecursive(
            Path rootPath,
            SearchOptions options,
            AtomicBoolean cancel,
            boolean includeMetadata
    ) throws IOException {
        long start = System.nanoTime();
        Path root = PathUtil.resolveForAccess(rootPath);

        if (options.isEmpty()) {
            return new SearchResult(root, List.of(), 0);
        }

        List<FileEntry> results = new ArrayList<>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (cancel.get()) {
                    return FileVisitResult.TERMINATE;
                }
                if (!dir.equals(root)) {
                    addIfMatches(dir, attrs);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (cancel.get()) {
                    return FileVisitResult.TERMINATE;
                }
                addIfMatches(file, attrs);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }

            private void addIfMatches(Path path, BasicFileAttributes attrs) {
                boolean isDirectory = attrs.isDirectory();
                String name = path.getFileName().toString();
                if (SearchMatcher.matches(root, path, name, isDirectory, options)) {
                    Long size = null;
                    Instant modified = null;
                    if (includeMetadata) {
                        size = isDirectory ? null : attrs.size();
                        modified = attrs.lastModifiedTime().toInstant();
                    }
                    results.add(new FileEntry(name, path, isDirectory, size, modified));
                }
            }
        });

        results.sort(Comparator
                .comparing(FileEntry::directory).reversed()
                .thenComparing(e -> e.path().toString().toLowerCase()));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        return new SearchResult(root, results, elapsedMs);
    }

    /**
     * フォルダ以下のパス一覧を走査し、コールバックへ渡す（サイズ・更新日時は取得しない）。
     */
    public int indexTree(Path rootPath, AtomicBoolean cancel, Consumer<FileEntry> onEntry) throws IOException {
        Path root = PathUtil.resolveForAccess(rootPath);
        int[] count = {0};

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (cancel.get()) {
                    return FileVisitResult.TERMINATE;
                }
                if (!dir.equals(root)) {
                    emit(dir, attrs.isDirectory());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (cancel.get()) {
                    return FileVisitResult.TERMINATE;
                }
                emit(file, attrs.isDirectory());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }

            private void emit(Path path, boolean isDirectory) {
                String name = path.getFileName().toString();
                onEntry.accept(new FileEntry(name, path, isDirectory, null, null));
                count[0]++;
            }
        });

        return count[0];
    }
}
