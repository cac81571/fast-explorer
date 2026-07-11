package com.fastexplorer.fs;

import com.fastexplorer.cache.CacheRepository;
import com.fastexplorer.model.FileEntry;
import com.fastexplorer.model.GrepOptions;
import com.fastexplorer.model.GrepResult;
import com.fastexplorer.model.ListDirectoryResult;
import com.fastexplorer.model.SearchOptions;
import com.fastexplorer.model.SearchResult;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
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
 * - 検索は H2 インデックスを優先、不足分は FS 走査でキャッシュを充実
 */
public final class CachedFileSystemService {

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
                        ListDirectoryResult result = new ListDirectoryResult(
                                listing.path(),
                                listing.entries(),
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
            boolean forceRefresh,
            Consumer<SearchResult> onBackgroundRefresh
    ) throws IOException {
        long start = System.nanoTime();

        if (!forceRefresh && options.isSimpleNameQuery()) {
            String q = options.simpleNameQuery();
            if (!q.isEmpty()) {
                try {
                    List<FileEntry> cachedHits = cache.searchByName(rootPath, q);
                    if (!cachedHits.isEmpty()) {
                        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                        SearchResult result = new SearchResult(rootPath, cachedHits, elapsedMs, true);

                        if (onBackgroundRefresh != null) {
                            scheduleSearchRefresh(rootPath, options, cancel, onBackgroundRefresh);
                        }
                        return result;
                    }
                } catch (SQLException ignored) {
                }
            }
        }

        SearchResult fresh = delegate.searchRecursive(rootPath, options, cancel);
        indexSearchResults(fresh.entries());
        return fresh;
    }

    public Path getParent(Path path) {
        return delegate.getParent(path);
    }

    public FileEntry toFileEntry(Path path) {
        return delegate.toFileEntry(path);
    }

    public GrepResult grepText(
            GrepOptions options,
            String pattern,
            boolean regex,
            boolean caseSensitive,
            boolean recursive,
            AtomicBoolean cancel
    ) throws IOException {
        return grepService.grep(options, pattern, regex, caseSensitive, recursive, cancel);
    }

    public GrepResult grepPaths(
            Collection<Path> paths,
            GrepOptions options,
            String pattern,
            boolean regex,
            boolean caseSensitive,
            AtomicBoolean cancel
    ) throws IOException {
        return grepService.grepPaths(paths, options, pattern, regex, caseSensitive, cancel);
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

    private void scheduleSearchRefresh(
            Path rootPath,
            SearchOptions options,
            AtomicBoolean cancel,
            Consumer<SearchResult> callback
    ) {
        refreshExecutor.submit(() -> {
            try {
                SearchResult fresh = delegate.searchRecursive(rootPath, options, cancel);
                indexSearchResults(fresh.entries());
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
