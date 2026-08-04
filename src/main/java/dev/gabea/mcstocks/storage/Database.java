package dev.gabea.mcstocks.storage;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Database implements AutoCloseable {
    public enum Type {
        SQLITE,
        MYSQL
    }

    private final JavaPlugin plugin;
    private final Type type;
    private final File sqliteFile;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final ExecutorService executor;
    private volatile Connection connection;

    public Database(JavaPlugin plugin) throws SQLException {
        this.plugin = plugin;
        FileConfiguration config = plugin.getConfig();
        String configured = config.getString("database.type", "sqlite");
        this.type = "mysql".equalsIgnoreCase(configured) || "mariadb".equalsIgnoreCase(configured)
                ? Type.MYSQL
                : Type.SQLITE;

        plugin.getDataFolder().mkdirs();
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MC-Stocks-DB");
            thread.setDaemon(true);
            return thread;
        });

        if (type == Type.SQLITE) {
            String fileName = config.getString("database.sqlite.file", "mcstocks.db");
            this.sqliteFile = new File(plugin.getDataFolder(), fileName);
            this.jdbcUrl = "jdbc:sqlite:" + sqliteFile.getAbsolutePath();
            this.username = null;
            this.password = null;
        } else {
            this.sqliteFile = null;
            String host = config.getString("database.mysql.host", "127.0.0.1");
            int port = config.getInt("database.mysql.port", 3306);
            String database = config.getString("database.mysql.database", "mcstocks");
            boolean ssl = config.getBoolean("database.mysql.use-ssl", false);
            this.jdbcUrl = "jdbc:mariadb://" + host + ":" + port + "/" + database
                    + "?useSSL=" + ssl
                    + "&allowPublicKeyRetrieval=true"
                    + "&autoReconnect=true";
            this.username = config.getString("database.mysql.username", "mcstocks");
            this.password = config.getString("database.mysql.password", "");
            try {
                Class.forName("org.mariadb.jdbc.Driver");
            } catch (ClassNotFoundException ex) {
                throw new SQLException("MariaDB/MySQL driver missing from the shaded jar.", ex);
            }
        }

        openConnection();
        migrate();
    }

    public Type type() {
        return type;
    }

    public File sqliteFile() {
        return sqliteFile;
    }

    public Connection connection() throws SQLException {
        ensureOpen();
        return connection;
    }

    public <T> T sync(SqlCallable<T> work) throws SQLException {
        if (Thread.currentThread().getName().equals("MC-Stocks-DB")) {
            return work.call();
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return work.call();
                } catch (SQLException ex) {
                    throw new CompletionException(ex);
                }
            }, executor).join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof SQLException sqlException) {
                throw sqlException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new SQLException(cause);
        }
    }

    public void sync(SqlRunnable work) throws SQLException {
        sync(() -> {
            work.run();
            return null;
        });
    }

    public CompletableFuture<Void> runAsync(SqlRunnable work) {
        return CompletableFuture.runAsync(() -> {
            try {
                work.run();
            } catch (SQLException ex) {
                throw new CompletionException(ex);
            }
        }, executor).exceptionally(error -> {
            plugin.getLogger().warning("Async database task failed: " + rootMessage(error));
            return null;
        });
    }

    public <T> CompletableFuture<T> supplyAsync(SqlCallable<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.call();
            } catch (SQLException ex) {
                throw new CompletionException(ex);
            }
        }, executor);
    }

    public String upsertHoldingSql() {
        if (type == Type.MYSQL) {
            return """
                    INSERT INTO portfolios (uuid, symbol, quantity, average_cost)
                    VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE quantity = VALUES(quantity), average_cost = VALUES(average_cost)
                    """;
        }
        return """
                INSERT INTO portfolios (uuid, symbol, quantity, average_cost)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(uuid, symbol) DO UPDATE SET quantity = excluded.quantity, average_cost = excluded.average_cost
                """;
    }

    public String upsertPlayerStatsSql() {
        if (type == Type.MYSQL) {
            return """
                    INSERT INTO player_stats (uuid, realized_profit)
                    VALUES (?, ?)
                    ON DUPLICATE KEY UPDATE realized_profit = realized_profit + VALUES(realized_profit)
                    """;
        }
        return """
                INSERT INTO player_stats (uuid, realized_profit)
                VALUES (?, ?)
                ON CONFLICT(uuid) DO UPDATE SET realized_profit = realized_profit + excluded.realized_profit
                """;
    }

    public String upsertMarketStateSql() {
        if (type == Type.MYSQL) {
            return """
                    INSERT INTO market_state (symbol, price, open_price, previous_price, frozen, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE price = VALUES(price), open_price = VALUES(open_price),
                    previous_price = VALUES(previous_price), frozen = VALUES(frozen), updated_at = VALUES(updated_at)
                    """;
        }
        return """
                INSERT INTO market_state (symbol, price, open_price, previous_price, frozen, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(symbol) DO UPDATE SET
                    price = excluded.price,
                    open_price = excluded.open_price,
                    previous_price = excluded.previous_price,
                    frozen = excluded.frozen,
                    updated_at = excluded.updated_at
                """;
    }

    public String upsertMetaSql() {
        if (type == Type.MYSQL) {
            return """
                    INSERT INTO plugin_meta (meta_key, meta_value)
                    VALUES (?, ?)
                    ON DUPLICATE KEY UPDATE meta_value = VALUES(meta_value)
                    """;
        }
        return """
                INSERT INTO plugin_meta (meta_key, meta_value)
                VALUES (?, ?)
                ON CONFLICT(meta_key) DO UPDATE SET meta_value = excluded.meta_value
                """;
    }

    public void setMeta(String key, String value) throws SQLException {
        try (PreparedStatement statement = connection().prepareStatement(upsertMetaSql())) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    public String getMeta(String key, String fallback) throws SQLException {
        try (PreparedStatement statement = connection().prepareStatement(
                "SELECT meta_value FROM plugin_meta WHERE meta_key = ?")) {
            statement.setString(1, key);
            try (ResultSet results = statement.executeQuery()) {
                if (results.next()) {
                    return results.getString("meta_value");
                }
            }
        }
        return fallback;
    }

    public void checkpoint() throws SQLException {
        if (type != Type.SQLITE) {
            return;
        }
        try (Statement statement = connection().createStatement()) {
            statement.executeUpdate("PRAGMA wal_checkpoint(FULL)");
        }
    }

    private void openConnection() throws SQLException {
        if (type == Type.SQLITE) {
            connection = DriverManager.getConnection(jdbcUrl);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("PRAGMA journal_mode = WAL");
                statement.executeUpdate("PRAGMA busy_timeout = 5000");
                statement.executeUpdate("PRAGMA foreign_keys = ON");
            }
        } else {
            connection = DriverManager.getConnection(jdbcUrl, username, password);
        }
        connection.setAutoCommit(true);
    }

    private void ensureOpen() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            openConnection();
        }
    }

    private void migrate() throws SQLException {
        String idColumn = type == Type.MYSQL
                ? "BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT"
                : "INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT";
        String booleanType = type == Type.MYSQL ? "TINYINT(1)" : "INTEGER";

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS portfolios (
                        uuid VARCHAR(36) NOT NULL,
                        symbol VARCHAR(32) NOT NULL,
                        quantity DOUBLE NOT NULL,
                        average_cost DOUBLE NOT NULL,
                        PRIMARY KEY (uuid, symbol)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS trade_history (
                        id %s,
                        uuid VARCHAR(36) NOT NULL,
                        symbol VARCHAR(32) NOT NULL,
                        side VARCHAR(16) NOT NULL,
                        quantity DOUBLE NOT NULL,
                        price DOUBLE NOT NULL,
                        gross DOUBLE NOT NULL,
                        fee DOUBLE NOT NULL,
                        tax DOUBLE NOT NULL DEFAULT 0,
                        reversed %s NOT NULL DEFAULT 0,
                        created_at BIGINT NOT NULL
                    )
                    """.formatted(idColumn, booleanType));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_stats (
                        uuid VARCHAR(36) PRIMARY KEY,
                        realized_profit DOUBLE NOT NULL DEFAULT 0
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS price_history (
                        id %s,
                        symbol VARCHAR(32) NOT NULL,
                        price DOUBLE NOT NULL,
                        recorded_at BIGINT NOT NULL
                    )
                    """.formatted(idColumn));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS limit_orders (
                        id %s,
                        uuid VARCHAR(36) NOT NULL,
                        symbol VARCHAR(32) NOT NULL,
                        side VARCHAR(16) NOT NULL,
                        quantity DOUBLE NOT NULL,
                        target_price DOUBLE NOT NULL,
                        status VARCHAR(16) NOT NULL,
                        created_at BIGINT NOT NULL,
                        executed_at BIGINT
                    )
                    """.formatted(idColumn));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS market_state (
                        symbol VARCHAR(32) PRIMARY KEY,
                        price DOUBLE NOT NULL,
                        open_price DOUBLE NOT NULL,
                        previous_price DOUBLE NOT NULL,
                        frozen %s NOT NULL DEFAULT 0,
                        updated_at BIGINT NOT NULL
                    )
                    """.formatted(booleanType));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS audit_log (
                        id %s,
                        actor VARCHAR(64) NOT NULL,
                        action VARCHAR(64) NOT NULL,
                        details TEXT NOT NULL,
                        created_at BIGINT NOT NULL
                    )
                    """.formatted(idColumn));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS plugin_meta (
                        meta_key VARCHAR(64) PRIMARY KEY,
                        meta_value TEXT NOT NULL
                    )
                    """);

            ensureColumn(statement, "trade_history", "tax", "DOUBLE NOT NULL DEFAULT 0");
            ensureColumn(statement, "trade_history", "reversed", booleanType + " NOT NULL DEFAULT 0");

            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_trade_history_uuid_created ON trade_history (uuid, created_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_trade_history_symbol_created ON trade_history (symbol, created_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_price_history_symbol_recorded ON price_history (symbol, recorded_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_limit_orders_status_symbol ON limit_orders (status, symbol)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_limit_orders_uuid_status ON limit_orders (uuid, status)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_log_created ON audit_log (created_at)");
        }
    }

    private void ensureColumn(Statement statement, String table, String column, String definition) {
        try {
            if (type == Type.SQLITE) {
                try (ResultSet results = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
                    while (results.next()) {
                        if (column.equalsIgnoreCase(results.getString("name"))) {
                            return;
                        }
                    }
                }
                statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            } else {
                try (ResultSet results = connection.getMetaData().getColumns(null, null, table, column)) {
                    if (results.next()) {
                        return;
                    }
                }
                statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
        } catch (SQLException ignored) {
            // Column already exists on some dialects / older migrations.
        }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    @Override
    public void close() throws SQLException {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    @FunctionalInterface
    public interface SqlCallable<T> {
        T call() throws SQLException;
    }

    @FunctionalInterface
    public interface SqlRunnable {
        void run() throws SQLException;
    }
}
