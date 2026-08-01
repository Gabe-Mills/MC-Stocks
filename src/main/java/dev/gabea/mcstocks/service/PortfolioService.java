package dev.gabea.mcstocks.service;

import dev.gabea.mcstocks.model.Asset;
import dev.gabea.mcstocks.model.Holding;
import dev.gabea.mcstocks.model.LeaderboardEntry;
import dev.gabea.mcstocks.model.MarketState;
import dev.gabea.mcstocks.model.PlayerGainEntry;
import dev.gabea.mcstocks.model.TradeResult;
import dev.gabea.mcstocks.storage.Database;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class PortfolioService {
    private final Database database;
    private final MarketService marketService;
    private final EconomyService economyService;

    public PortfolioService(Database database, MarketService marketService, EconomyService economyService) {
        this.database = database;
        this.marketService = marketService;
        this.economyService = economyService;
    }

    public synchronized TradeResult buy(Player player, String rawSymbol, double quantity) throws SQLException {
        String symbol = normalize(rawSymbol);
        MarketState state = marketService.state(symbol).orElse(null);
        TradeResult validation = validateTrade(state, symbol, quantity);
        if (validation != null) {
            return validation;
        }
        if (!economyService.available()) {
            return TradeResult.failure("no-vault", symbol);
        }

        Asset asset = state.asset();
        if (!quantityAllowed(asset, quantity)) {
            return TradeResult.failure("whole-units-only", symbol);
        }

        double gross = state.price() * quantity;
        double fee = fee(gross);
        double total = gross + fee;
        TradeResult valueValidation = validateTradeValue(symbol, gross);
        if (valueValidation != null) {
            return valueValidation;
        }
        if (!economyService.has(player, total)) {
            return TradeResult.failure("insufficient-funds", symbol, quantity, gross, fee, total);
        }
        if (!economyService.withdraw(player, total)) {
            return TradeResult.failure("insufficient-funds", symbol, quantity, gross, fee, total);
        }

        try {
            database.connection().setAutoCommit(false);
            Holding current = holding(player.getUniqueId(), symbol);
            double newQuantity = current.quantity() + quantity;
            double newAverageCost = ((current.quantity() * current.averageCost()) + gross) / newQuantity;
            upsertHolding(player.getUniqueId(), symbol, newQuantity, newAverageCost);
            insertTrade(player.getUniqueId(), symbol, "BUY", quantity, state.price(), gross, fee);
            database.connection().commit();
        } catch (SQLException ex) {
            rollbackQuietly();
            economyService.deposit(player, total);
            throw ex;
        } finally {
            database.connection().setAutoCommit(true);
        }
        return TradeResult.success("buy-success", symbol, quantity, gross, fee, total);
    }

    public synchronized TradeResult sell(Player player, String rawSymbol, double quantity) throws SQLException {
        String symbol = normalize(rawSymbol);
        MarketState state = marketService.state(symbol).orElse(null);
        TradeResult validation = validateTrade(state, symbol, quantity);
        if (validation != null) {
            return validation;
        }
        if (!economyService.available()) {
            return TradeResult.failure("no-vault", symbol);
        }

        Asset asset = state.asset();
        if (!quantityAllowed(asset, quantity)) {
            return TradeResult.failure("whole-units-only", symbol);
        }

        Holding current = holding(player.getUniqueId(), symbol);
        if (current.quantity() + 0.000001 < quantity) {
            return TradeResult.failure("insufficient-holdings", symbol);
        }

        double gross = state.price() * quantity;
        double fee = fee(gross);
        double total = gross - fee;
        TradeResult valueValidation = validateTradeValue(symbol, gross);
        if (valueValidation != null) {
            return valueValidation;
        }
        if (!economyService.deposit(player, total)) {
            return TradeResult.failure("no-vault", symbol);
        }

        try {
            database.connection().setAutoCommit(false);
            double remaining = current.quantity() - quantity;
            if (remaining <= 0.000001) {
                deleteHolding(player.getUniqueId(), symbol);
            } else {
                upsertHolding(player.getUniqueId(), symbol, remaining, current.averageCost());
            }

            double realizedProfit = ((state.price() - current.averageCost()) * quantity) - fee;
            addRealizedProfit(player.getUniqueId(), realizedProfit);
            insertTrade(player.getUniqueId(), symbol, "SELL", quantity, state.price(), gross, fee);
            database.connection().commit();
        } catch (SQLException ex) {
            rollbackQuietly();
            economyService.withdraw(player, total);
            throw ex;
        } finally {
            database.connection().setAutoCommit(true);
        }
        return TradeResult.success("sell-success", symbol, quantity, gross, fee, total);
    }

    public List<Holding> holdings(UUID playerId) throws SQLException {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT symbol, quantity, average_cost FROM portfolios WHERE uuid = ? ORDER BY symbol")) {
            statement.setString(1, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                List<Holding> holdings = new ArrayList<>();
                while (results.next()) {
                    holdings.add(new Holding(
                            results.getString("symbol"),
                            results.getDouble("quantity"),
                            results.getDouble("average_cost")
                    ));
                }
                return holdings;
            }
        }
    }

    public Holding holding(UUID playerId, String symbol) throws SQLException {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT quantity, average_cost FROM portfolios WHERE uuid = ? AND symbol = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, normalize(symbol));
            try (ResultSet results = statement.executeQuery()) {
                if (results.next()) {
                    return new Holding(normalize(symbol), results.getDouble("quantity"), results.getDouble("average_cost"));
                }
                return new Holding(normalize(symbol), 0.0, 0.0);
            }
        }
    }

    public double portfolioValue(UUID playerId) throws SQLException {
        double total = 0.0;
        for (Holding holding : holdings(playerId)) {
            total += marketService.state(holding.symbol())
                    .map(state -> holding.marketValue(state.price()))
                    .orElse(0.0);
        }
        return total;
    }

    public List<LeaderboardEntry> topPortfolioValues(int limit) throws SQLException {
        Map<String, Double> prices = marketService.allStates().stream()
                .collect(java.util.stream.Collectors.toMap(state -> state.asset().symbol(), MarketState::price));
        try (Statement statement = database.connection().createStatement();
             ResultSet results = statement.executeQuery("SELECT uuid, symbol, quantity FROM portfolios")) {
            java.util.Map<UUID, Double> values = new java.util.HashMap<>();
            while (results.next()) {
                UUID playerId = UUID.fromString(results.getString("uuid"));
                double price = prices.getOrDefault(results.getString("symbol"), 0.0);
                values.merge(playerId, results.getDouble("quantity") * price, Double::sum);
            }
            return values.entrySet().stream()
                    .map(entry -> new LeaderboardEntry(entry.getKey(), entry.getValue()))
                    .sorted((left, right) -> Double.compare(right.value(), left.value()))
                    .limit(limit)
                    .toList();
        }
    }

    public List<LeaderboardEntry> topRealizedProfit(int limit) throws SQLException {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT uuid, realized_profit FROM player_stats ORDER BY realized_profit DESC LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet results = statement.executeQuery()) {
                List<LeaderboardEntry> entries = new ArrayList<>();
                while (results.next()) {
                    entries.add(new LeaderboardEntry(
                            UUID.fromString(results.getString("uuid")),
                            results.getDouble("realized_profit")
                    ));
                }
                return entries;
            }
        }
    }

    public List<PlayerGainEntry> topDailyGains(int limit, double minimumStartValue) throws SQLException {
        record GainValues(double startValue, double currentValue) {
        }

        java.util.Map<String, MarketState> states = marketService.allStates().stream()
                .collect(java.util.stream.Collectors.toMap(state -> state.asset().symbol(), state -> state));
        try (Statement statement = database.connection().createStatement();
             ResultSet results = statement.executeQuery("SELECT uuid, symbol, quantity FROM portfolios")) {
            java.util.Map<UUID, GainValues> values = new java.util.HashMap<>();
            while (results.next()) {
                MarketState state = states.get(results.getString("symbol"));
                if (state == null) {
                    continue;
                }
                UUID playerId = UUID.fromString(results.getString("uuid"));
                double quantity = results.getDouble("quantity");
                double startValue = state.openPrice() * quantity;
                double currentValue = state.price() * quantity;
                values.merge(
                        playerId,
                        new GainValues(startValue, currentValue),
                        (left, right) -> new GainValues(left.startValue() + right.startValue(), left.currentValue() + right.currentValue())
                );
            }
            return values.entrySet().stream()
                    .filter(entry -> entry.getValue().startValue() >= minimumStartValue)
                    .map(entry -> {
                        GainValues value = entry.getValue();
                        double gainPercent = value.startValue() <= 0.0 ? 0.0 : ((value.currentValue() - value.startValue()) / value.startValue()) * 100.0;
                        return new PlayerGainEntry(entry.getKey(), gainPercent, value.startValue(), value.currentValue());
                    })
                    .sorted((left, right) -> Double.compare(right.gainPercent(), left.gainPercent()))
                    .limit(limit)
                    .toList();
        }
    }

    private TradeResult validateTrade(MarketState state, String symbol, double quantity) {
        if (!marketService.isOpen()) {
            return TradeResult.failure("market-closed", symbol);
        }
        if (state == null) {
            return TradeResult.failure("asset-not-found", symbol);
        }
        if (!state.asset().enabled()) {
            return TradeResult.failure("asset-disabled", symbol);
        }
        if (Double.isNaN(quantity) || Double.isInfinite(quantity) || quantity <= 0.0) {
            return TradeResult.failure("invalid-amount", symbol);
        }
        return null;
    }

    private boolean quantityAllowed(Asset asset, double quantity) {
        return asset.decimalTrading() || Math.rint(quantity) == quantity;
    }

    private TradeResult validateTradeValue(String symbol, double gross) {
        if (gross < marketService.rules().minTradeValue()) {
            return TradeResult.failure("trade-too-small", symbol, 0.0, gross, 0.0, marketService.rules().minTradeValue());
        }
        if (gross > marketService.rules().maxTradeValue()) {
            return TradeResult.failure("trade-too-large", symbol, 0.0, gross, 0.0, marketService.rules().maxTradeValue());
        }
        return null;
    }

    private double fee(double gross) {
        return gross * (marketService.feePercent() / 100.0);
    }

    private void upsertHolding(UUID playerId, String symbol, double quantity, double averageCost) throws SQLException {
        try (PreparedStatement statement = database.connection().prepareStatement("""
                INSERT INTO portfolios (uuid, symbol, quantity, average_cost)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(uuid, symbol) DO UPDATE SET quantity = excluded.quantity, average_cost = excluded.average_cost
                """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, normalize(symbol));
            statement.setDouble(3, quantity);
            statement.setDouble(4, averageCost);
            statement.executeUpdate();
        }
    }

    private void deleteHolding(UUID playerId, String symbol) throws SQLException {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "DELETE FROM portfolios WHERE uuid = ? AND symbol = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, normalize(symbol));
            statement.executeUpdate();
        }
    }

    private void insertTrade(UUID playerId, String symbol, String side, double quantity, double price, double gross, double fee) throws SQLException {
        try (PreparedStatement statement = database.connection().prepareStatement("""
                INSERT INTO trade_history (uuid, symbol, side, quantity, price, gross, fee, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, normalize(symbol));
            statement.setString(3, side);
            statement.setDouble(4, quantity);
            statement.setDouble(5, price);
            statement.setDouble(6, gross);
            statement.setDouble(7, fee);
            statement.setLong(8, Instant.now().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private void addRealizedProfit(UUID playerId, double profit) throws SQLException {
        try (PreparedStatement statement = database.connection().prepareStatement("""
                INSERT INTO player_stats (uuid, realized_profit)
                VALUES (?, ?)
                ON CONFLICT(uuid) DO UPDATE SET realized_profit = realized_profit + excluded.realized_profit
                """)) {
            statement.setString(1, playerId.toString());
            statement.setDouble(2, profit);
            statement.executeUpdate();
        }
    }

    private String normalize(String symbol) {
        return symbol == null ? "" : symbol.toUpperCase(Locale.ROOT);
    }

    private void rollbackQuietly() {
        try {
            database.connection().rollback();
        } catch (SQLException ignored) {
        }
    }
}
