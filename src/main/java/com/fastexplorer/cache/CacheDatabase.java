package com.fastexplorer.cache;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class CacheDatabase {

    private static final CacheDatabase INSTANCE = new CacheDatabase();

    private Connection connection;

    private CacheDatabase() {}

    public static CacheDatabase getInstance() {
        return INSTANCE;
    }

    public synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            open();
        } else {
            // 既存接続でもマイグレーションを再適用（アプリ更新後も欠けたテーブルを補完）
            initSchema(connection);
        }
        return connection;
    }

    private void open() throws SQLException {
        Path cacheDir = Path.of(System.getProperty("user.home"), ".fast-explorer");
        try {
            Files.createDirectories(cacheDir);
        } catch (Exception e) {
            throw new SQLException("キャッシュディレクトリを作成できません: " + cacheDir, e);
        }

        String dbPath = cacheDir.resolve("cache").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:h2:" + dbPath
                + ";AUTO_SERVER=FALSE"
                + ";CACHE_SIZE=65536"
                + ";LOCK_TIMEOUT=10000";

        connection = DriverManager.getConnection(url, "sa", "");
        initSchema(connection);
    }

    private static void initSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS dir_cache (
                        path VARCHAR(2048) PRIMARY KEY,
                        cached_at TIMESTAMP NOT NULL,
                        entry_count INT NOT NULL,
                        include_size BOOLEAN NOT NULL
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS file_cache (
                        path VARCHAR(2048) PRIMARY KEY,
                        parent_path VARCHAR(2048) NOT NULL,
                        name VARCHAR(512) NOT NULL,
                        is_directory BOOLEAN NOT NULL,
                        size BIGINT,
                        modified TIMESTAMP,
                        cached_at TIMESTAMP NOT NULL
                    )
                    """);
            st.execute("""
                    CREATE INDEX IF NOT EXISTS idx_file_cache_parent
                    ON file_cache (parent_path)
                    """);
            st.execute("""
                    CREATE INDEX IF NOT EXISTS idx_file_cache_name_lower
                    ON file_cache (LOWER(name))
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS tree_index (
                        root_path VARCHAR(2048) PRIMARY KEY,
                        indexed_at TIMESTAMP NOT NULL,
                        entry_count INT NOT NULL,
                        complete BOOLEAN NOT NULL
                    )
                    """);
        }
    }

    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
            connection = null;
        }
    }
}
