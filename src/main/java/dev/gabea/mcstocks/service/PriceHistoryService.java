package dev.gabea.mcstocks.service;

import dev.gabea.mcstocks.model.MarketState;
import dev.gabea.mcstocks.model.PricePoint;
import dev.gabea.mcstocks.storage.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class PriceHistoryService {
    private static final char[] SPARKLINE = {'_', '.', ':', '-', '=', '+', '*', '#'};

    private final Database database;
    private int limitPerAsset;

    public PriceHistoryService(Database database, int limitPerAsset) {
        this.database = database;
        this.limitPerAsset = Math.max(50, limitPerAsset);
    }

    public void reload(int limitPerAsset) {
        this.limitPerAsset = Math.max(50, limitPerAsset);
    }

    public void record(Collection<MarketState> states) throws SQLException {
        long now = Instant.now().toEpochMilli();
        try (PreparedStatement statement = database.connection().prepareStatement(
                "INSERT INTO price_history (symbol, price, recorded_at) VALUES (?, ?, ?)")) {
            for (MarketState state : states) {
                statement.setString(1, state.asset().symbol());
                statement.setDouble(2, state.price());
                statement.setLong(3, now);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        prune(states);
    }

    public List<PricePoint> recent(String symbol, int limit) throws SQLException {
        try (PreparedStatement statement = database.connection().prepareStatement("""
                SELECT symbol, price, recorded_at
                FROM price_history
                WHERE symbol = ?
                ORDER BY recorded_at DESC
                LIMIT ?
                """)) {
            statement.setString(1, symbol.toUpperCase(Locale.ROOT));
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet results = statement.executeQuery()) {
                List<PricePoint> points = new ArrayList<>();
                while (results.next()) {
                    points.add(new PricePoint(
                            results.getString("symbol"),
                            results.getDouble("price"),
                            results.getLong("recorded_at")
                    ));
                }
                points.sort(Comparator.comparingLong(PricePoint::recordedAt));
                return points;
            }
        }
    }

    public String sparkline(String symbol, int limit) throws SQLException {
        List<PricePoint> points = recent(symbol, limit);
        if (points.size() < 2) {
            return "no history yet";
        }
        double min = points.stream().mapToDouble(PricePoint::price).min().orElse(0.0);
        double max = points.stream().mapToDouble(PricePoint::price).max().orElse(0.0);
        if (Math.abs(max - min) < 0.000001) {
            return String.valueOf(SPARKLINE[0]).repeat(points.size());
        }
        StringBuilder builder = new StringBuilder();
        for (PricePoint point : points) {
            double normalized = (point.price() - min) / (max - min);
            int index = (int) Math.round(normalized * (SPARKLINE.length - 1));
            builder.append(SPARKLINE[index]);
        }
        return builder.toString();
    }

    private void prune(Collection<MarketState> states) throws SQLException {
        try (PreparedStatement statement = database.connection().prepareStatement("""
                DELETE FROM price_history
                WHERE symbol = ?
                AND id NOT IN (
                    SELECT id FROM price_history
                    WHERE symbol = ?
                    ORDER BY recorded_at DESC
                    LIMIT ?
                )
                """)) {
            for (MarketState state : states) {
                statement.setString(1, state.asset().symbol());
                statement.setString(2, state.asset().symbol());
                statement.setInt(3, limitPerAsset);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }
}
