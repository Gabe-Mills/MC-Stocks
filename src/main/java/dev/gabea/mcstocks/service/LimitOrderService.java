package dev.gabea.mcstocks.service;

import dev.gabea.mcstocks.model.Asset;
import dev.gabea.mcstocks.model.LimitOrder;
import dev.gabea.mcstocks.model.MarketState;
import dev.gabea.mcstocks.model.OrderSide;
import dev.gabea.mcstocks.model.TradeResult;
import dev.gabea.mcstocks.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Map;

public final class LimitOrderService {
    private final Database database;
    private final MarketService marketService;
    private final PortfolioService portfolioService;
    private final EconomyService economyService;
    private final dev.gabea.mcstocks.config.MessageService messages;
    private boolean enabled;
    private int maxOpenPerPlayer;

    public LimitOrderService(Database database, MarketService marketService, PortfolioService portfolioService, EconomyService economyService, dev.gabea.mcstocks.config.MessageService messages, boolean enabled, int maxOpenPerPlayer) {
        this.database = database;
        this.marketService = marketService;
        this.portfolioService = portfolioService;
        this.economyService = economyService;
        this.messages = messages;
        this.enabled = enabled;
        this.maxOpenPerPlayer = Math.max(1, maxOpenPerPlayer);
    }

    public void reload(boolean enabled, int maxOpenPerPlayer) {
        this.enabled = enabled;
        this.maxOpenPerPlayer = Math.max(1, maxOpenPerPlayer);
    }

    public long create(Player player, OrderSide side, String rawSymbol, double quantity, double targetPrice) throws SQLException {
        if (!enabled) {
            throw new IllegalStateException("limit-disabled");
        }
        if (openOrderCount(player.getUniqueId()) >= maxOpenPerPlayer) {
            throw new IllegalArgumentException("limit-cap-reached");
        }
        String symbol = normalize(rawSymbol);
        MarketState state = marketService.state(symbol).orElseThrow(() -> new IllegalArgumentException("asset-not-found"));
        Asset asset = state.asset();
        if (!asset.enabled()) {
            throw new IllegalArgumentException("asset-disabled");
        }
        if (Double.isNaN(quantity) || Double.isInfinite(quantity) || quantity <= 0.0 || Double.isNaN(targetPrice) || Double.isInfinite(targetPrice) || targetPrice <= 0.0) {
            throw new IllegalArgumentException("invalid-amount");
        }
        if (!asset.decimalTrading() && Math.rint(quantity) != quantity) {
            throw new IllegalArgumentException("whole-units-only");
        }
        double estimatedGross = quantity * targetPrice;
        if (estimatedGross < marketService.rules().minTradeValue()) {
            throw new IllegalArgumentException("trade-too-small");
        }
        if (estimatedGross > marketService.rules().maxTradeValue()) {
            throw new IllegalArgumentException("trade-too-large");
        }
        if (side == OrderSide.BUY && (!economyService.available() || !economyService.has(player, estimatedGross + fee(estimatedGross)))) {
            throw new IllegalArgumentException("insufficient-funds");
        }
        if (side == OrderSide.SELL && portfolioService.holding(player.getUniqueId(), symbol).quantity() + 0.000001 < quantity) {
            throw new IllegalArgumentException("insufficient-holdings");
        }

        try (PreparedStatement statement = database.connection().prepareStatement("""
                INSERT INTO limit_orders (uuid, symbol, side, quantity, target_price, status, created_at)
                VALUES (?, ?, ?, ?, ?, 'OPEN', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, player.getUniqueId().toString());
            statement.setString(2, symbol);
            statement.setString(3, side.name());
            statement.setDouble(4, quantity);
            statement.setDouble(5, targetPrice);
            statement.setLong(6, Instant.now().toEpochMilli());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("Could not create limit order.");
    }

    public boolean cancel(UUID playerId, long orderId) throws SQLException {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "UPDATE limit_orders SET status = 'CANCELLED', executed_at = ? WHERE id = ? AND uuid = ? AND status = 'OPEN'")) {
            statement.setLong(1, Instant.now().toEpochMilli());
            statement.setLong(2, orderId);
            statement.setString(3, playerId.toString());
            return statement.executeUpdate() > 0;
        }
    }

    public List<LimitOrder> openOrders(UUID playerId) throws SQLException {
        try (PreparedStatement statement = database.connection().prepareStatement("""
                SELECT id, uuid, symbol, side, quantity, target_price, status, created_at, executed_at
                FROM limit_orders
                WHERE uuid = ? AND status = 'OPEN'
                ORDER BY created_at DESC
                """)) {
            statement.setString(1, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                List<LimitOrder> orders = new ArrayList<>();
                while (results.next()) {
                    orders.add(readOrder(results));
                }
                return orders;
            }
        }
    }

    public List<LimitOrder> openOrdersForSymbol(String rawSymbol, int limit) throws SQLException {
        try (PreparedStatement statement = database.connection().prepareStatement("""
                SELECT id, uuid, symbol, side, quantity, target_price, status, created_at, executed_at
                FROM limit_orders
                WHERE symbol = ? AND status = 'OPEN'
                ORDER BY target_price ASC, created_at ASC
                LIMIT ?
                """)) {
            statement.setString(1, normalize(rawSymbol));
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet results = statement.executeQuery()) {
                List<LimitOrder> orders = new ArrayList<>();
                while (results.next()) {
                    orders.add(readOrder(results));
                }
                return orders;
            }
        }
    }

    public void processOpenOrders() throws SQLException {
        if (!enabled || !marketService.isOpen()) {
            return;
        }
        for (LimitOrder order : executableOrders()) {
            Player player = Bukkit.getPlayer(order.playerId());
            if (player == null || !player.isOnline()) {
                continue;
            }
            TradeResult result = order.side() == OrderSide.BUY
                    ? portfolioService.buy(player, order.symbol(), order.quantity())
                    : portfolioService.sell(player, order.symbol(), order.quantity());
            if (result.success()) {
                mark(order.id(), "FILLED");
                player.sendMessage(messages.format("limit-filled", Map.of(
                        "id", String.valueOf(order.id()),
                        "side", order.side().name(),
                        "symbol", order.symbol(),
                        "quantity", dev.gabea.mcstocks.util.Formats.quantity(order.quantity())
                )));
            } else if ("insufficient-funds".equals(result.messageKey()) || "insufficient-holdings".equals(result.messageKey())) {
                mark(order.id(), "FAILED");
                player.sendMessage(messages.format("limit-failed", Map.of(
                        "id", String.valueOf(order.id()),
                        "reason", result.messageKey()
                )));
            }
        }
    }

    private int openOrderCount(UUID playerId) throws SQLException {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT COUNT(*) FROM limit_orders WHERE uuid = ? AND status = 'OPEN'")) {
            statement.setString(1, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : 0;
            }
        }
    }

    private List<LimitOrder> executableOrders() throws SQLException {
        try (PreparedStatement statement = database.connection().prepareStatement("""
                SELECT id, uuid, symbol, side, quantity, target_price, status, created_at, executed_at
                FROM limit_orders
                WHERE status = 'OPEN'
                ORDER BY created_at ASC
                """);
             ResultSet results = statement.executeQuery()) {
            List<LimitOrder> orders = new ArrayList<>();
            while (results.next()) {
                LimitOrder order = readOrder(results);
                MarketState state = marketService.state(order.symbol()).orElse(null);
                if (state == null || !state.asset().enabled()) {
                    continue;
                }
                boolean executable = order.side() == OrderSide.BUY
                        ? state.price() <= order.targetPrice()
                        : state.price() >= order.targetPrice();
                if (executable) {
                    orders.add(order);
                }
            }
            return orders;
        }
    }

    private void mark(long id, String status) throws SQLException {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "UPDATE limit_orders SET status = ?, executed_at = ? WHERE id = ?")) {
            statement.setString(1, status);
            statement.setLong(2, Instant.now().toEpochMilli());
            statement.setLong(3, id);
            statement.executeUpdate();
        }
    }

    private LimitOrder readOrder(ResultSet results) throws SQLException {
        long executedAt = results.getLong("executed_at");
        boolean executedAtWasNull = results.wasNull();
        return new LimitOrder(
                results.getLong("id"),
                UUID.fromString(results.getString("uuid")),
                results.getString("symbol"),
                OrderSide.valueOf(results.getString("side")),
                results.getDouble("quantity"),
                results.getDouble("target_price"),
                results.getString("status"),
                results.getLong("created_at"),
                executedAtWasNull ? null : executedAt
        );
    }

    private double fee(double gross) {
        return gross * (marketService.feePercent() / 100.0);
    }

    private String normalize(String symbol) {
        return symbol == null ? "" : symbol.toUpperCase(Locale.ROOT);
    }
}
