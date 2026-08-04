package dev.gabea.mcstocks.service;

import dev.gabea.mcstocks.model.Asset;
import dev.gabea.mcstocks.model.Holding;
import dev.gabea.mcstocks.model.LeaderboardEntry;
import dev.gabea.mcstocks.model.MarketState;
import dev.gabea.mcstocks.model.PlayerGainEntry;
import dev.gabea.mcstocks.model.TradeRecord;
import dev.gabea.mcstocks.model.TradeResult;
import dev.gabea.mcstocks.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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
    private final TradeLockService tradeLocks;
    private final AuditService auditService;

    public PortfolioService(
            Database database,
            MarketService marketService,
            EconomyService economyService,
            TradeLockService tradeLocks,
            AuditService auditService
    ) {
        this.database = database;
        this.marketService = marketService;
        this.economyService = economyService;
        this.tradeLocks = tradeLocks;
        this.auditService = auditService;
    }

    public TradeResult buy(Player player, String rawSymbol, double quantity) throws SQLException {
        try {
            return tradeLocks.withLock(player.getUniqueId(), () -> buyLocked(player, rawSymbol, quantity));
        } catch (IllegalStateException ex) {
            return TradeResult.failure("trade-busy", normalize(rawSymbol));
        } catch (SQLException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new SQLException(ex);
        }
    }

    public TradeResult sell(Player player, String rawSymbol, double quantity) throws SQLException {
        try {
            return tradeLocks.withLock(player.getUniqueId(), () -> sellLocked(player, rawSymbol, quantity));
        } catch (IllegalStateException ex) {
            return TradeResult.failure("trade-busy", normalize(rawSymbol));
        } catch (SQLException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new SQLException(ex);
        }
    }

    private TradeResult buyLocked(Player player, String rawSymbol, double quantity) throws SQLException {
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
        double tax = tax(gross);
        double total = gross + fee + tax;
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
            database.sync(() -> {
                database.connection().setAutoCommit(false);
                try {
                    Holding current = holdingUnsafe(player.getUniqueId(), symbol);
                    double newQuantity = current.quantity() + quantity;
                    double newAverageCost = ((current.quantity() * current.averageCost()) + gross) / newQuantity;
                    upsertHolding(player.getUniqueId(), symbol, newQuantity, newAverageCost);
                    insertTrade(player.getUniqueId(), symbol, "BUY", quantity, state.price(), gross, fee, tax);
                    database.connection().commit();
                } catch (SQLException ex) {
                    rollbackQuietly();
                    throw ex;
                } finally {
                    database.connection().setAutoCommit(true);
                }
                return null;
            });
        } catch (SQLException ex) {
            economyService.deposit(player, total);
            throw ex;
        }
        auditService.log(player.getUniqueId().toString(), "BUY", symbol + " qty=" + quantity + " total=" + total);
        return TradeResult.success("buy-success", symbol, quantity, gross, fee, total);
    }

    private TradeResult sellLocked(Player player, String rawSymbol, double quantity) throws SQLException {
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
        double tax = tax(gross);
        double total = gross - fee - tax;
        TradeResult valueValidation = validateTradeValue(symbol, gross);
        if (valueValidation != null) {
            return valueValidation;
        }
        if (total < 0.0) {
            return TradeResult.failure("trade-too-small", symbol, quantity, gross, fee, total);
        }
        if (!economyService.deposit(player, total)) {
            return TradeResult.failure("no-vault", symbol);
        }

        try {
            database.sync(() -> {
                database.connection().setAutoCommit(false);
                try {
                    Holding latest = holdingUnsafe(player.getUniqueId(), symbol);
                    if (latest.quantity() + 0.000001 < quantity) {
                        throw new SQLException("insufficient-holdings");
                    }
                    double remaining = latest.quantity() - quantity;
                    if (remaining <= 0.000001) {
                        deleteHolding(player.getUniqueId(), symbol);
                    } else {
                        upsertHolding(player.getUniqueId(), symbol, remaining, latest.averageCost());
                    }

                    double realizedProfit = ((state.price() - latest.averageCost()) * quantity) - fee - tax;
                    addRealizedProfit(player.getUniqueId(), realizedProfit);
                    insertTrade(player.getUniqueId(), symbol, "SELL", quantity, state.price(), gross, fee, tax);
                    database.connection().commit();
                } catch (SQLException ex) {
                    rollbackQuietly();
                    throw ex;
                } finally {
                    database.connection().setAutoCommit(true);
                }
                return null;
            });
        } catch (SQLException ex) {
            economyService.withdraw(player, total);
            if ("insufficient-holdings".equals(ex.getMessage())) {
                return TradeResult.failure("insufficient-holdings", symbol);
            }
            throw ex;
        }
        auditService.log(player.getUniqueId().toString(), "SELL", symbol + " qty=" + quantity + " total=" + total);
        return TradeResult.success("sell-success", symbol, quantity, gross, fee, total);
    }

    public TradeResult reverseTrade(String actor, long tradeId) throws SQLException {
        TradeRecord trade = findTrade(tradeId);
        if (trade == null) {
            return TradeResult.failure("trade-not-found", "");
        }
        if (trade.reversed()) {
            return TradeResult.failure("trade-already-reversed", trade.symbol());
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(trade.playerId());
        Player online = offlinePlayer.getPlayer();
        if (online == null || !online.isOnline()) {
            return TradeResult.failure("player-offline", trade.symbol());
        }

        try {
            return tradeLocks.withLock(trade.playerId(), () -> reverseLocked(actor, online, trade));
        } catch (IllegalStateException ex) {
            return TradeResult.failure("trade-busy", trade.symbol());
        } catch (SQLException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new SQLException(ex);
        }
    }

    private TradeResult reverseLocked(String actor, Player player, TradeRecord trade) throws SQLException {
        if (!economyService.available()) {
            return TradeResult.failure("no-vault", trade.symbol());
        }

        double netPaid = trade.gross() + trade.fee() + trade.tax();
        double netReceived = trade.gross() - trade.fee() - trade.tax();

        if ("BUY".equalsIgnoreCase(trade.side())) {
            Holding current = holding(player.getUniqueId(), trade.symbol());
            if (current.quantity() + 0.000001 < trade.quantity()) {
                return TradeResult.failure("insufficient-holdings", trade.symbol());
            }
            if (!economyService.deposit(player, netPaid)) {
                return TradeResult.failure("no-vault", trade.symbol());
            }
            try {
                database.sync(() -> {
                    database.connection().setAutoCommit(false);
                    try {
                        Holding latest = holdingUnsafe(player.getUniqueId(), trade.symbol());
                        double remaining = latest.quantity() - trade.quantity();
                        if (remaining <= 0.000001) {
                            deleteHolding(player.getUniqueId(), trade.symbol());
                        } else {
                            upsertHolding(player.getUniqueId(), trade.symbol(), remaining, latest.averageCost());
                        }
                        markReversed(trade.id());
                        database.connection().commit();
                    } catch (SQLException ex) {
                        rollbackQuietly();
                        throw ex;
                    } finally {
                        database.connection().setAutoCommit(true);
                    }
                    return null;
                });
            } catch (SQLException ex) {
                economyService.withdraw(player, netPaid);
                throw ex;
            }
        } else if ("SELL".equalsIgnoreCase(trade.side())) {
            if (!economyService.has(player, netReceived) || !economyService.withdraw(player, netReceived)) {
                return TradeResult.failure("insufficient-funds", trade.symbol(), trade.quantity(), trade.gross(), trade.fee(), netReceived);
            }
            try {
                database.sync(() -> {
                    database.connection().setAutoCommit(false);
                    try {
                        Holding latest = holdingUnsafe(player.getUniqueId(), trade.symbol());
                        double newQuantity = latest.quantity() + trade.quantity();
                        double newAverage = latest.quantity() <= 0.000001
                                ? trade.price()
                                : ((latest.quantity() * latest.averageCost()) + trade.gross()) / newQuantity;
                        upsertHolding(player.getUniqueId(), trade.symbol(), newQuantity, newAverage);
                        addRealizedProfit(player.getUniqueId(), -(((trade.price() - latest.averageCost()) * trade.quantity()) - trade.fee() - trade.tax()));
                        markReversed(trade.id());
                        database.connection().commit();
                    } catch (SQLException ex) {
                        rollbackQuietly();
                        throw ex;
                    } finally {
                        database.connection().setAutoCommit(true);
                    }
                    return null;
                });
            } catch (SQLException ex) {
                economyService.deposit(player, netReceived);
                throw ex;
            }
        } else {
            return TradeResult.failure("trade-not-found", trade.symbol());
        }

        auditService.log(actor, "REVERSE", "tradeId=" + trade.id() + " " + trade.side() + " " + trade.symbol());
        return TradeResult.success("trade-reversed", trade.symbol(), trade.quantity(), trade.gross(), trade.fee(), netPaid);
    }

    public TradeRecord findTrade(long tradeId) throws SQLException {
        return database.sync(() -> {
            try (PreparedStatement statement = database.connection().prepareStatement("""
                    SELECT id, uuid, symbol, side, quantity, price, gross, fee, tax, reversed, created_at
                    FROM trade_history
                    WHERE id = ?
                    """)) {
                statement.setLong(1, tradeId);
                try (ResultSet results = statement.executeQuery()) {
                    if (!results.next()) {
                        return null;
                    }
                    return readTrade(results);
                }
            }
        });
    }

    public List<Holding> holdings(UUID playerId) throws SQLException {
        return database.sync(() -> {
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
        });
    }

    public Holding holding(UUID playerId, String symbol) throws SQLException {
        return database.sync(() -> holdingUnsafe(playerId, symbol));
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
        return database.sync(() -> {
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
        });
    }

    public List<LeaderboardEntry> topRealizedProfit(int limit) throws SQLException {
        return database.sync(() -> {
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
        });
    }

    public List<PlayerGainEntry> topDailyGains(int limit, double minimumStartValue) throws SQLException {
        record GainValues(double startValue, double currentValue) {
        }

        java.util.Map<String, MarketState> states = marketService.allStates().stream()
                .collect(java.util.stream.Collectors.toMap(state -> state.asset().symbol(), state -> state));
        return database.sync(() -> {
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
        });
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
        if (state.frozen()) {
            return TradeResult.failure("asset-frozen", symbol);
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

    private double tax(double gross) {
        return gross * (marketService.taxPercent() / 100.0);
    }

    private Holding holdingUnsafe(UUID playerId, String symbol) throws SQLException {
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

    private void upsertHolding(UUID playerId, String symbol, double quantity, double averageCost) throws SQLException {
        try (PreparedStatement statement = database.connection().prepareStatement(database.upsertHoldingSql())) {
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

    private void insertTrade(UUID playerId, String symbol, String side, double quantity, double price, double gross, double fee, double tax) throws SQLException {
        try (PreparedStatement statement = database.connection().prepareStatement("""
                INSERT INTO trade_history (uuid, symbol, side, quantity, price, gross, fee, tax, reversed, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, normalize(symbol));
            statement.setString(3, side);
            statement.setDouble(4, quantity);
            statement.setDouble(5, price);
            statement.setDouble(6, gross);
            statement.setDouble(7, fee);
            statement.setDouble(8, tax);
            statement.setLong(9, Instant.now().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private void markReversed(long tradeId) throws SQLException {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "UPDATE trade_history SET reversed = 1 WHERE id = ?")) {
            statement.setLong(1, tradeId);
            statement.executeUpdate();
        }
    }

    private void addRealizedProfit(UUID playerId, double profit) throws SQLException {
        try (PreparedStatement statement = database.connection().prepareStatement(database.upsertPlayerStatsSql())) {
            statement.setString(1, playerId.toString());
            statement.setDouble(2, profit);
            statement.executeUpdate();
        }
    }

    private TradeRecord readTrade(ResultSet results) throws SQLException {
        return new TradeRecord(
                results.getLong("id"),
                UUID.fromString(results.getString("uuid")),
                results.getString("symbol"),
                results.getString("side"),
                results.getDouble("quantity"),
                results.getDouble("price"),
                results.getDouble("gross"),
                results.getDouble("fee"),
                results.getDouble("tax"),
                results.getInt("reversed") != 0,
                results.getLong("created_at")
        );
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
