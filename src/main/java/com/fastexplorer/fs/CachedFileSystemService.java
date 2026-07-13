package com.fastexplorer.fs;

import com.fastexplorer.cache.CacheRepository;
import com.fastexplorer.model.FileEntry;
import com.fastexplorer.model.GrepOptions;
import com.fastexplorer.model.GrepResult;
import com.fastexplorer.model.ListDirectoryResult;
import com.fastexplorer.model.SearchOptions;
import com.fastexplorer.model.SearchResult;
import com.fastexplorer.model.TaskProgress;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * H2 キャッシュを最大限活用:
 * - キャッシュヒット時は即座に返却
 * - 期限切れキャッシュは stale-while-revalidate（表示後にバックグラウンド更新）
 * - 検索はまず UNC 以下を H2 にインデックス化し、H2 上で条件検索
 */
public final class CachedFileSystemService {

    private static final int INDEX_BATCH_SIZE = 500;

    private final FileSystemService delegate = new FileSystemService();
    private final TextGrepService grepService = new TextGrepService();
    private final CacheRepository cache = new CacheRepository();
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fast-explorer-cache-refresh");
        t.setDaemon(true);
        return t;
    });

    public ListDirectoryResult listDirectory(
            Path dirPath,
            boolean includeSize,
            boolean forceRefresh,
            Consumer<ListDirectoryResult> onBackgroundRefresh
    ) throws IOException {
        long start = System.nanoTime();

        if (!forceRefresh) {
            try {
                Optional<CacheRepository.CachedListing> cached = cache.findListing(dirPath);
                if (cached.isPresent()) {
                    CacheRepository.CachedListing listing = cached.get();
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

                    if (listing.isFresh() || listing.isStaleButUsable()) {
                        List<FileEntry> entries = includeSize
                                ? listing.entries()
                                : stripMetadata(listing.entries());
                        ListDirectoryResult result = new ListDirectoryResult(
                                listing.path(),
                                entries,
                                elapsedMs,
                                true,
                                false
                        );

                        if (!listing.isFresh() && onBackgroundRefresh != null) {
                            scheduleDirectoryRefresh(dirPath, includeSize, onBackgroundRefresh);
                        }
                        return result;
                    }
                }
            } catch (SQLException e) {
                // キャッシュ障害時は FS にフォールバック
            }
        } else {
            try {
                cache.invalidate(dirPath);
            } catch (SQLException ignored) {
            }
        }

        ListDirectoryResult fresh = delegate.listDirectory(dirPath, includeSize);
        persistListing(fresh, includeSize);
        return fresh;
    }

    public SearchResult searchRecursive(
            Path rootPath,
            SearchOptions options,
            AtomicBoolean cancel,
            boolean includeMetadata,
            boolean forceRefresh,
            Consumer<SearchResult> onBackgroundRefresh
    ) throws IOException {
        long start = System.nanoTime();

        try {
            if (forceRefresh) {
                cache.invalidateTree(rootPath);
            }

            Optional<CacheRepository.TreeIndex> index = cache.findTreeIndex(rootPath);
            boolean needCrawl = forceRefresh
                    || index.isEmpty()
                    || !index.get().complete();

            if (!needCrawl && index.get().isStaleButUsable() && !index.get().isFresh()) {
                SearchResult cached = searchFromCache(rootPath, options, includeMetadata, start);
                if (onBackgroundRefresh != null) {
                    scheduleTreeReindex(rootPath, cancel, includeMetadata, options, onBackgroundRefresh);
                }
                return cached;
            }

            if (needCrawl) {
                indexTreeToCache(rootPath, cancel);
                if (cancel.get()) {
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                    return new SearchResult(rootPath, List.of(), elapsedMs, true);
                }
            }

            return searchFromCache(rootPath, options, includeMetadata, start);
        } catch (SQLException e) {
            // キャッシュ障害時は FS にフォールバック
        }

        SearchResult fresh = delegate.searchRecursive(rootPath, options, cancel, includeMetadata);
        indexSearchResults(fresh.entries());
        try {
            cache.saveTreeIndex(rootPath, fresh.entries().size(), !cancel.get());
        } catch (SQLException ignored) {
        }
        return fresh;
    }

    public Path getParent(Path path) {
        return delegate.getParent(path);
    }

    public FileEntry toFileEntry(Path path, boolean includeMetadata) {
        return delegate.toFileEntry(path, includeMetadata);
    }

    public GrepResult grepText(
            GrepOptions options,
            String pattern,
            boolean regex,
            boolean caseSensitive,
            boolean recursive,
            int contextLines,
            AtomicBoolean cancel,
            long progressStart,
            Consumer<TaskProgress> onProgress
    ) throws IOException {
        return grepService.grep(
                options, pattern, regex, caseSensitive, recursive, contextLines, cancel, progressStart, onProgress);
    }

    public GrepResult grepPaths(
            Collection<Path> paths,
            GrepOptions options,
            String pattern,
            boolean regex,
            boolean caseSensitive,
            int contextLines,
            AtomicBoolean cancel,
            long progressStart,
            Consumer<TaskProgress> onProgress
    ) throws IOException {
        return grepService.grepPaths(
                paths, options, pattern, regex, caseSensitive, contextLines, cancel, progressStart, onProgress);
    }

    public CacheRepository.CacheStats getCacheStats() throws SQLException {
        return cache.getStats();
    }

    public void clearCache() throws SQLException {
        cache.clearAll();
    }

    public void shutdown() {
        refreshExecutor.shutdownNow();
    }

    private SearchResult searchFromCache(
            Path rootPath,
            SearchOptions options,
            boolean includeMetadata,
            long startNanos
    ) throws SQLException {
        List<FileEntry> hits = cache.search(rootPath, options);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        List<FileEntry> entries = includeMetadata ? hits : stripMetadata(hits);
        return new SearchResult(rootPath, entries, elapsedMs, true);
    }

    private void indexTreeToCache(Path rootPath, AtomicBoolean cancel) throws IOException, SQLException {
        List<FileEntry> batch = new ArrayList<>(INDEX_BATCH_SIZE);
        int[] count = {0};

        delegate.indexTree(rootPath, cancel, entry -> {
            batch.add(entry);
            count[0]++;
            if (batch.size() >= INDEX_BATCH_SIZE) {
                flushIndexBatch(batch);
            }
        });

        if (!batch.isEmpty()) {
            flushIndexBatch(batch);
        }

        cache.saveTreeIndex(rootPath, count[0], !cancel.get());
    }

    private void flushIndexBatch(List<FileEntry> batch) {
        try {
            cache.batchUpsertEntries(batch);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            batch.clear();
        }
    }

    private void scheduleDirectoryRefresh(
            Path dirPath,
            boolean includeSize,
            Consumer<ListDirectoryResult> callback
    ) {
        refreshExecutor.submit(() -> {
            try {
                ListDirectoryResult fresh = delegate.listDirectory(dirPath, includeSize);
                persistListing(fresh, includeSize);
                callback.accept(new ListDirectoryResult(
                        fresh.path(),
                        fresh.entries(),
                        fresh.elapsedMs(),
                        false,
                        true
                ));
            } catch (Exception ignored) {
            }
        });
    }

    private void scheduleTreeReindex(
            Path rootPath,
            AtomicBoolean cancel,
            boolean includeMetadata,
            SearchOptions options,
            Consumer<SearchResult> callback
    ) {
        refreshExecutor.submit(() -> {
            try {
                cache.invalidateTree(rootPath);
                indexTreeToCache(rootPath, cancel);
                if (cancel.get()) {
                    return;
                }
                SearchResult fresh = searchFromCache(rootPath, options, includeMetadata, System.nanoTime());
                callback.accept(new SearchResult(
                        fresh.root(),
                        fresh.entries(),
                        fresh.elapsedMs(),
                        false
                ));
            } catch (Exception ignored) {
            }
        });
    }

    private static List<FileEntry> stripMetadata(List<FileEntry> entries) {
        return entries.stream()
                .map(entry -> new FileEntry(
                        entry.name(),
                        entry.path(),
                        entry.directory(),
                        null,
                        null
                ))
                .toList();
    }

    private void persistListing(ListDirectoryResult result, boolean includeSize) {
        try {
            cache.saveListing(result.path(), result.entries(), includeSize);
        } catch (SQLException ignored) {
        }
    }

    private void indexSearchResults(List<FileEntry> entries) {
        for (FileEntry entry : entries) {
            try {
                Path parent = entry.path().getParent();
                if (parent != null) {
                    cache.upsertEntry(parent, entry);
                }
            } catch (SQLException ignored) {
            }
        }
    }
}
