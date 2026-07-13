package com.fastexplorer.cache;

import com.fastexplorer.model.FileEntry;
import com.fastexplorer.model.SearchOptions;
import com.fastexplorer.util.PathUtil;
import com.fastexplorer.util.SearchMatcher;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class CacheRepository {

    static final long FRESH_MS = 2 * 60 * 1000L;
    static final long STALE_MS = 30 * 60 * 1000L;

    private final CacheDatabase database;

    public CacheRepository() {
        this(CacheDatabase.getInstance());
    }

    CacheRepository(CacheDatabase database) {
        this.database = database;
    }

    public Optional<CachedListing> findListing(Path dirPath) throws SQLException {
        String pathKey = toKey(dirPath);
        Connection conn = database.getConnection();

        Instant cachedAt;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT cached_at, entry_count FROM dir_cache WHERE path = ?")) {
            ps.setString(1, pathKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                cachedAt = rs.getTimestamp("cached_at").toInstant();
            }
        }

        List<FileEntry> entries = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT path, name, is_directory, size, modified
                FROM file_cache
                WHERE parent_path = ?
                """)) {
            ps.setString(1, pathKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(toEntry(rs));
                }
            }
        }

        entries.sort(Comparator
                .comparing(FileEntry::directory).reversed()
                .thenComparing(e -> e.name().toLowerCase()));

        return Optional.of(new CachedListing(dirPath, entries, cachedAt));
    }

    public void saveListing(Path dirPath, List<FileEntry> entries, boolean includeSize) throws SQLException {
        String pathKey = toKey(dirPath);
        Instant now = Instant.now();
        Connection conn = database.getConnection();

        conn.setAutoCommit(false);
        try {
            try (PreparedStatement deleteFiles = conn.prepareStatement(
                    "DELETE FROM file_cache WHERE parent_path = ?")) {
                deleteFiles.setString(1, pathKey);
                deleteFiles.executeUpdate();
            }

            try (PreparedStatement insertFile = conn.prepareStatement("""
                    INSERT INTO file_cache
                    (path, parent_path, name, is_directory, size, modified, cached_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (FileEntry entry : entries) {
                    insertFile.setString(1, toKey(entry.path()));
                    insertFile.setString(2, pathKey);
                    insertFile.setString(3, entry.name());
                    insertFile.setBoolean(4, entry.directory());
                    if (entry.size() != null) {
                        insertFile.setLong(5, entry.size());
                    } else {
                        insertFile.setNull(5, Types.BIGINT);
                    }
                    if (entry.modified() != null) {
                        insertFile.setTimestamp(6, Timestamp.from(entry.modified()));
                    } else {
                        insertFile.setNull(6, Types.TIMESTAMP);
                    }
                    insertFile.setTimestamp(7, Timestamp.from(now));
                    insertFile.addBatch();
                }
                insertFile.executeBatch();
            }

            try (PreparedStatement upsertDir = conn.prepareStatement("""
                    MERGE INTO dir_cache (path, cached_at, entry_count, include_size)
                    KEY (path)
                    VALUES (?, ?, ?, ?)
                    """)) {
                upsertDir.setString(1, pathKey);
                upsertDir.setTimestamp(2, Timestamp.from(now));
                upsertDir.setInt(3, entries.size());
                upsertDir.setBoolean(4, includeSize);
                upsertDir.executeUpdate();
            }

            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public void invalidate(Path dirPath) throws SQLException {
        String pathKey = toKey(dirPath);
        Connection conn = database.getConnection();
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM file_cache WHERE parent_path = ?")) {
                ps.setString(1, pathKey);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM dir_cache WHERE path = ?")) {
                ps.setString(1, pathKey);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public List<FileEntry> searchByName(Path root, String query) throws SQLException {
        List<FileEntry> results = new ArrayList<>();
        String normalizedQuery = query.trim().toLowerCase();
        for (FileEntry entry : listUnderRoot(toKey(root))) {
            if (entry.name().toLowerCase().contains(normalizedQuery)) {
                results.add(entry);
            }
        }

        results.sort(Comparator
                .comparing(FileEntry::directory).reversed()
                .thenComparing(e -> e.path().toString().toLowerCase()));
        return results;
    }

    public List<FileEntry> search(Path root, SearchOptions options) throws SQLException {
        if (options.isEmpty()) {
            return List.of();
        }

        List<FileEntry> results = new ArrayList<>();
        for (FileEntry entry : listUnderRoot(toKey(root))) {
            if (SearchMatcher.matches(root, entry.path(), entry.name(), entry.directory(), options)) {
                results.add(entry);
            }
        }

        results.sort(Comparator
                .comparing(FileEntry::directory).reversed()
                .thenComparing(e -> e.path().toString().toLowerCase()));
        return results;
    }

    public Optional<TreeIndex> findTreeIndex(Path root) throws SQLException {
        String rootKey = toKey(root);
        Connection conn = database.getConnection();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT indexed_at, entry_count, complete
                FROM tree_index
                WHERE root_path = ?
                """)) {
            ps.setString(1, rootKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new TreeIndex(
                        root,
                        rs.getTimestamp("indexed_at").toInstant(),
                        rs.getInt("entry_count"),
                        rs.getBoolean("complete")
                ));
            }
        }
    }

    public void saveTreeIndex(Path root, int entryCount, boolean complete) throws SQLException {
        String rootKey = toKey(root);
        Connection conn = database.getConnection();
        try (PreparedStatement ps = conn.prepareStatement("""
                MERGE INTO tree_index (root_path, indexed_at, entry_count, complete)
                KEY (root_path)
                VALUES (?, ?, ?, ?)
                """)) {
            ps.setString(1, rootKey);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setInt(3, entryCount);
            ps.setBoolean(4, complete);
            ps.executeUpdate();
        }
    }

    public void invalidateTree(Path root) throws SQLException {
        String rootKey = toKey(root);
        Connection conn = database.getConnection();
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement ps = conn.prepareStatement("""
                    DELETE FROM file_cache
                    WHERE path = ? OR path LIKE ? ESCAPE '!'
                    """)) {
                ps.setString(1, rootKey);
                ps.setString(2, escapeLike(rootKey) + "%");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM tree_index WHERE root_path = ?")) {
                ps.setString(1, rootKey);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public void batchUpsertEntries(List<FileEntry> entries) throws SQLException {
        if (entries.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        Connection conn = database.getConnection();
        try (PreparedStatement ps = conn.prepareStatement("""
                MERGE INTO file_cache
                (path, parent_path, name, is_directory, size, modified, cached_at)
                KEY (path)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (FileEntry entry : entries) {
                Path parent = entry.path().getParent();
                if (parent == null) {
                    continue;
                }
                ps.setString(1, toKey(entry.path()));
                ps.setString(2, toKey(parent));
                ps.setString(3, entry.name());
                ps.setBoolean(4, entry.directory());
                if (entry.size() != null) {
                    ps.setLong(5, entry.size());
                } else {
                    ps.setNull(5, Types.BIGINT);
                }
                if (entry.modified() != null) {
                    ps.setTimestamp(6, Timestamp.from(entry.modified()));
                } else {
                    ps.setNull(6, Types.TIMESTAMP);
                }
                ps.setTimestamp(7, Timestamp.from(now));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private List<FileEntry> listUnderRoot(String rootKey) throws SQLException {
        List<FileEntry> entries = new ArrayList<>();
        Connection conn = database.getConnection();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT path, name, is_directory, size, modified
                FROM file_cache
                WHERE path = ? OR path LIKE ? ESCAPE '!'
                """)) {
            ps.setString(1, rootKey);
            ps.setString(2, escapeLike(rootKey) + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(toEntry(rs));
                }
            }
        }
        return entries;
    }

    public void upsertEntry(Path parent, FileEntry entry) throws SQLException {
        String parentKey = toKey(parent);
        Instant now = Instant.now();
        Connection conn = database.getConnection();
        try (PreparedStatement ps = conn.prepareStatement("""
                MERGE INTO file_cache
                (path, parent_path, name, is_directory, size, modified, cached_at)
                KEY (path)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, toKey(entry.path()));
            ps.setString(2, parentKey);
            ps.setString(3, entry.name());
            ps.setBoolean(4, entry.directory());
            if (entry.size() != null) {
                ps.setLong(5, entry.size());
            } else {
                ps.setNull(5, Types.BIGINT);
            }
            if (entry.modified() != null) {
                ps.setTimestamp(6, Timestamp.from(entry.modified()));
            } else {
                ps.setNull(6, Types.TIMESTAMP);
            }
            ps.setTimestamp(7, Timestamp.from(now));
            ps.executeUpdate();
        }
    }

    public CacheStats getStats() throws SQLException {
        Connection conn = database.getConnection();
        long dirs;
        long files;
        try (Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM dir_cache")) {
                rs.next();
                dirs = rs.getLong(1);
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM file_cache")) {
                rs.next();
                files = rs.getLong(1);
            }
        }
        return new CacheStats(dirs, files);
    }

    public void clearAll() throws SQLException {
        Connection conn = database.getConnection();
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM file_cache");
            st.execute("DELETE FROM dir_cache");
            st.execute("DELETE FROM tree_index");
        }
    }

    static String toKey(Path path) {
        return PathUtil.toDisplay(path);
    }

    private static FileEntry toEntry(ResultSet rs) throws SQLException {
        Path entryPath = PathUtil.parse(rs.getString("path"));
        String name = rs.getString("name");
        boolean isDirectory = rs.getBoolean("is_directory");
        long sizeVal = rs.getLong("size");
        Long size = rs.wasNull() ? null : sizeVal;
        Timestamp modifiedTs = rs.getTimestamp("modified");
        Instant modified = modifiedTs != null ? modifiedTs.toInstant() : null;
        return new FileEntry(name, entryPath, isDirectory, size, modified);
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    public record CachedListing(Path path, List<FileEntry> entries, Instant cachedAt) {
        public long ageMs() {
            return System.currentTimeMillis() - cachedAt.toEpochMilli();
        }

        public boolean isFresh() {
            return ageMs() < FRESH_MS;
        }

        public boolean isStaleButUsable() {
            return ageMs() < STALE_MS;
        }
    }

    public record TreeIndex(Path root, Instant indexedAt, int entryCount, boolean complete) {
        public long ageMs() {
            return System.currentTimeMillis() - indexedAt.toEpochMilli();
        }

        public boolean isFresh() {
            return complete && ageMs() < FRESH_MS;
        }

        public boolean isStaleButUsable() {
            return complete && ageMs() < STALE_MS;
        }
    }

    public record CacheStats(long directoryCount, long fileCount) {}
}
