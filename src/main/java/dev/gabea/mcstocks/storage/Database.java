package dev.gabea.mcstocks.storage;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class Database implements AutoCloseable {
    private final Connection connection;

    public Database(JavaPlugin plugin) throws SQLException {
        plugin.getDataFolder().mkdirs();
        File file = new File(plugin.getDataFolder(), "mcstocks.db");
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        migrate();
    }

    public Connection connection() {
        return connection;
    }

    private void migrate() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("PRAGMA journal_mode = WAL");
            statement.executeUpdate("PRAGMA busy_timeout = 5000");
            statement.executeUpdate("PRAGMA foreign_keys = ON");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS portfolios (
                        uuid TEXT NOT NULL,
                        symbol TEXT NOT NULL,
                        quantity REAL NOT NULL,
                        average_cost REAL NOT NULL,
                        PRIMARY KEY (uuid, symbol)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS trade_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid TEXT NOT NULL,
                        symbol TEXT NOT NULL,
                        side TEXT NOT NULL,
                        quantity REAL NOT NULL,
                        price REAL NOT NULL,
                        gross REAL NOT NULL,
                        fee REAL NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_stats (
                        uuid TEXT PRIMARY KEY,
                        realized_profit REAL NOT NULL DEFAULT 0
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_trade_history_uuid_created ON trade_history (uuid, created_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_trade_history_symbol_created ON trade_history (symbol, created_at)");
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
