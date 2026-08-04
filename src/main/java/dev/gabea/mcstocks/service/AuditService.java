package dev.gabea.mcstocks.service;

import dev.gabea.mcstocks.storage.Database;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

public final class AuditService {
    private final Database database;

    public AuditService(Database database) {
        this.database = database;
    }

    public void log(String actor, String action, String details) {
        database.runAsync(() -> {
            try (PreparedStatement statement = database.connection().prepareStatement("""
                    INSERT INTO audit_log (actor, action, details, created_at)
                    VALUES (?, ?, ?, ?)
                    """)) {
                statement.setString(1, actor);
                statement.setString(2, action);
                statement.setString(3, details);
                statement.setLong(4, Instant.now().toEpochMilli());
                statement.executeUpdate();
            }
        });
    }

    public void logSync(String actor, String action, String details) throws SQLException {
        database.sync(() -> {
            try (PreparedStatement statement = database.connection().prepareStatement("""
                    INSERT INTO audit_log (actor, action, details, created_at)
                    VALUES (?, ?, ?, ?)
                    """)) {
                statement.setString(1, actor);
                statement.setString(2, action);
                statement.setString(3, details);
                statement.setLong(4, Instant.now().toEpochMilli());
                statement.executeUpdate();
            }
            return null;
        });
    }
}
