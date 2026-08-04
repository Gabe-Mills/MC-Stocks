package dev.gabea.mcstocks.service;

import dev.gabea.mcstocks.storage.Database;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.stream.Stream;

public final class BackupService {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final JavaPlugin plugin;
    private final Database database;
    private boolean enabled;
    private int keep;

    public BackupService(JavaPlugin plugin, Database database, FileConfiguration config) {
        this.plugin = plugin;
        this.database = database;
        reload(config);
    }

    public void reload(FileConfiguration config) {
        enabled = config.getBoolean("backups.enabled", true);
        keep = Math.max(1, config.getInt("backups.keep", 12));
    }

    public boolean enabled() {
        return enabled;
    }

    public void backupNow() {
        if (!enabled) {
            return;
        }
        database.runAsync(() -> {
            try {
                Path backupDir = plugin.getDataFolder().toPath().resolve("backups");
                Files.createDirectories(backupDir);
                String stamp = STAMP.format(Instant.now());
                if (database.type() == Database.Type.SQLITE && database.sqliteFile() != null) {
                    database.checkpoint();
                    Path target = backupDir.resolve("mcstocks-" + stamp + ".db");
                    Files.copy(database.sqliteFile().toPath(), target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Path target = backupDir.resolve("mcstocks-" + stamp + ".sql");
                    dumpSql(target);
                }
                prune(backupDir);
                plugin.getLogger().info("Created database backup " + stamp);
            } catch (IOException ex) {
                throw new SQLException("Backup failed: " + ex.getMessage(), ex);
            }
        });
    }

    private void dumpSql(Path target) throws SQLException, IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("-- MC-Stocks backup ").append(Instant.now()).append('\n');
        dumpTable(builder, "portfolios", "uuid, symbol, quantity, average_cost");
        dumpTable(builder, "trade_history", "id, uuid, symbol, side, quantity, price, gross, fee, tax, reversed, created_at");
        dumpTable(builder, "player_stats", "uuid, realized_profit");
        dumpTable(builder, "price_history", "id, symbol, price, recorded_at");
        dumpTable(builder, "limit_orders", "id, uuid, symbol, side, quantity, target_price, status, created_at, executed_at");
        dumpTable(builder, "market_state", "symbol, price, open_price, previous_price, frozen, updated_at");
        dumpTable(builder, "audit_log", "id, actor, action, details, created_at");
        dumpTable(builder, "plugin_meta", "meta_key, meta_value");
        Files.writeString(target, builder.toString());
    }

    private void dumpTable(StringBuilder builder, String table, String columns) throws SQLException {
        builder.append("\n-- ").append(table).append('\n');
        try (Statement statement = database.connection().createStatement();
             ResultSet results = statement.executeQuery("SELECT " + columns + " FROM " + table)) {
            int columnCount = results.getMetaData().getColumnCount();
            while (results.next()) {
                builder.append("INSERT INTO ").append(table).append(" (").append(columns).append(") VALUES (");
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        builder.append(", ");
                    }
                    Object value = results.getObject(i);
                    if (value == null) {
                        builder.append("NULL");
                    } else if (value instanceof Number || value instanceof Boolean) {
                        builder.append(value);
                    } else {
                        builder.append('\'').append(value.toString().replace("'", "''")).append('\'');
                    }
                }
                builder.append(");\n");
            }
        }
    }

    private void prune(Path backupDir) throws IOException {
        try (Stream<Path> files = Files.list(backupDir)) {
            files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), Comparator.reverseOrder()))
                    .skip(keep)
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }
}
