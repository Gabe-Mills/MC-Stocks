package dev.gabea.mcstocks.command;

import dev.gabea.mcstocks.MCStocksPlugin;
import dev.gabea.mcstocks.config.MessageService;
import dev.gabea.mcstocks.gui.StockMenus;
import dev.gabea.mcstocks.model.Holding;
import dev.gabea.mcstocks.model.LeaderboardEntry;
import dev.gabea.mcstocks.model.LimitOrder;
import dev.gabea.mcstocks.model.MarketState;
import dev.gabea.mcstocks.model.OrderSide;
import dev.gabea.mcstocks.model.PlayerGainEntry;
import dev.gabea.mcstocks.model.TradeResult;
import dev.gabea.mcstocks.service.LimitOrderService;
import dev.gabea.mcstocks.service.MarketService;
import dev.gabea.mcstocks.service.PortfolioService;
import dev.gabea.mcstocks.service.PriceHistoryService;
import dev.gabea.mcstocks.util.Formats;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class StocksCommand implements CommandExecutor, TabCompleter {
    private final MCStocksPlugin plugin;
    private final MessageService messages;
    private final MarketService marketService;
    private final PortfolioService portfolioService;
    private final PriceHistoryService priceHistoryService;
    private final LimitOrderService limitOrderService;
    private final StockMenus stockMenus;

    public StocksCommand(MCStocksPlugin plugin, MessageService messages, MarketService marketService, PortfolioService portfolioService, PriceHistoryService priceHistoryService, LimitOrderService limitOrderService, StockMenus stockMenus) {
        this.plugin = plugin;
        this.messages = messages;
        this.marketService = marketService;
        this.portfolioService = portfolioService;
        this.priceHistoryService = priceHistoryService;
        this.limitOrderService = limitOrderService;
        this.stockMenus = stockMenus;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (args.length == 0) {
            stockMenus.openMain(player);
            return true;
        }

        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "buy" -> trade(player, args, true);
                case "sell" -> trade(player, args, false);
                case "portfolio" -> showPortfolio(player);
                case "price" -> showPrice(player, args);
                case "top" -> showTop(player, args);
                case "movers" -> showMovers(player);
                case "limit" -> createLimitOrder(player, args);
                case "orders" -> showOrders(player);
                case "cancel" -> cancelOrder(player, args);
                case "orderbook" -> showOrderBook(player, args);
                case "history" -> showHistory(player, args);
                default -> stockMenus.openMain(player);
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Database error during /stocks: " + ex.getMessage());
            player.sendMessage("Database error. Check the server console.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("buy", "sell", "limit", "orders", "cancel", "orderbook", "history", "portfolio", "price", "top", "movers"), args[0]);
        }
        if (args.length == 2 && "limit".equalsIgnoreCase(args[0])) {
            return filter(List.of("buy", "sell"), args[1]);
        }
        if (args.length == 2 && List.of("buy", "sell", "price").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(marketService.allStates().stream().map(state -> state.asset().symbol()).toList(), args[1]);
        }
        if (args.length == 2 && List.of("orderbook", "history").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(marketService.allStates().stream().map(state -> state.asset().symbol()).toList(), args[1]);
        }
        if (args.length == 3 && "limit".equalsIgnoreCase(args[0]) && List.of("buy", "sell").contains(args[1].toLowerCase(Locale.ROOT))) {
            return filter(marketService.allStates().stream().map(state -> state.asset().symbol()).toList(), args[2]);
        }
        if (args.length == 2 && "top".equalsIgnoreCase(args[0])) {
            return filter(List.of("portfolio", "gain", "profit"), args[1]);
        }
        if (args.length == 3 && List.of("buy", "sell").contains(args[0].toLowerCase(Locale.ROOT))) {
            return List.of("1", "5", "10");
        }
        return List.of();
    }

    private void trade(Player player, String[] args, boolean buy) throws SQLException {
        if (args.length < 3) {
            player.sendMessage("/stocks " + (buy ? "buy" : "sell") + " <symbol> <amount>");
            return;
        }
        double quantity;
        try {
            quantity = Double.parseDouble(args[2]);
        } catch (NumberFormatException ex) {
            player.sendMessage(messages.get("invalid-amount"));
            return;
        }

        TradeResult result = buy
                ? portfolioService.buy(player, args[1], quantity)
                : portfolioService.sell(player, args[1], quantity);
        sendTradeResult(player, result);
    }

    private void showPortfolio(Player player) throws SQLException {
        List<Holding> holdings = portfolioService.holdings(player.getUniqueId());
        if (holdings.isEmpty()) {
            player.sendMessage("You do not own any assets yet.");
            return;
        }
        player.sendMessage("Portfolio value: " + Formats.money(portfolioService.portfolioValue(player.getUniqueId())));
        for (Holding holding : holdings) {
            double price = marketService.state(holding.symbol()).map(MarketState::price).orElse(0.0);
            player.sendMessage(holding.symbol() + ": " + Formats.quantity(holding.quantity())
                    + " @ avg " + Formats.money(holding.averageCost())
                    + " | value " + Formats.money(holding.marketValue(price)));
        }
    }

    private void showPrice(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("/stocks price <symbol>");
            return;
        }
        MarketState state = marketService.state(args[1]).orElse(null);
        if (state == null) {
            player.sendMessage(messages.format("asset-not-found", Map.of("symbol", args[1].toUpperCase(Locale.ROOT))));
            return;
        }
        player.sendMessage(state.asset().symbol() + " " + state.asset().name() + ": "
                + Formats.money(state.price()) + " (" + Formats.percent(state.changePercent()) + ")");
        try {
            player.sendMessage("History: " + priceHistoryService.sparkline(state.asset().symbol(), 16));
        } catch (SQLException ex) {
            plugin.getLogger().warning("Could not load price history: " + ex.getMessage());
        }
    }

    private void showTop(Player player, String[] args) throws SQLException {
        String type = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "portfolio";
        int limit = Math.max(1, plugin.getConfig().getInt("leaderboards.size", 10));
        if ("gain".equals(type)) {
            List<PlayerGainEntry> entries = portfolioService.topDailyGains(limit, plugin.getConfig().getDouble("leaderboards.min-daily-gain-value", 1.0));
            player.sendMessage("Top daily gains:");
            int rank = 1;
            for (PlayerGainEntry entry : entries) {
                OfflinePlayer target = Bukkit.getOfflinePlayer(entry.playerId());
                player.sendMessage(rank + ". " + playerName(target) + " - " + Formats.percent(entry.gainPercent())
                        + " (" + Formats.money(entry.startValue()) + " -> " + Formats.money(entry.currentValue()) + ")");
                rank++;
            }
            return;
        }

        List<LeaderboardEntry> entries = "profit".equals(type) ? portfolioService.topRealizedProfit(limit) : portfolioService.topPortfolioValues(limit);
        player.sendMessage("Top " + ("profit".equals(type) ? "realized profit" : "portfolio value") + ":");
        int rank = 1;
        for (LeaderboardEntry entry : entries) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(entry.playerId());
            player.sendMessage(rank + ". " + playerName(target) + " - " + Formats.money(entry.value()));
            rank++;
        }
    }

    private void showMovers(Player player) {
        player.sendMessage("Top market movers:");
        marketService.allStates().stream()
                .sorted(Comparator.comparingDouble((MarketState state) -> Math.abs(state.changePercent())).reversed())
                .limit(8)
                .forEach(state -> player.sendMessage(state.asset().symbol() + " - " + Formats.money(state.price())
                        + " (" + Formats.percent(state.changePercent()) + ")"));
    }

    private void createLimitOrder(Player player, String[] args) throws SQLException {
        if (args.length < 5) {
            player.sendMessage("/stocks limit <buy|sell> <symbol> <amount> <targetPrice>");
            return;
        }
        OrderSide side;
        try {
            side = OrderSide.valueOf(args[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            player.sendMessage("/stocks limit <buy|sell> <symbol> <amount> <targetPrice>");
            return;
        }

        double quantity;
        double targetPrice;
        try {
            quantity = Double.parseDouble(args[3]);
            targetPrice = Double.parseDouble(args[4]);
        } catch (NumberFormatException ex) {
            player.sendMessage(messages.get("invalid-amount"));
            return;
        }

        try {
            long id = limitOrderService.create(player, side, args[2], quantity, targetPrice);
            player.sendMessage(messages.format("limit-created", Map.of("id", String.valueOf(id))));
        } catch (IllegalStateException | IllegalArgumentException ex) {
            player.sendMessage(limitError(ex.getMessage(), args[2], quantity, targetPrice));
        }
    }

    private void showOrders(Player player) throws SQLException {
        List<LimitOrder> orders = limitOrderService.openOrders(player.getUniqueId());
        if (orders.isEmpty()) {
            player.sendMessage("You have no open limit orders.");
            return;
        }
        player.sendMessage("Open limit orders:");
        for (LimitOrder order : orders) {
            player.sendMessage("#" + order.id() + " " + order.side() + " " + order.symbol()
                    + " " + Formats.quantity(order.quantity()) + " @ " + Formats.money(order.targetPrice()));
        }
    }

    private void cancelOrder(Player player, String[] args) throws SQLException {
        if (args.length < 2) {
            player.sendMessage("/stocks cancel <orderId>");
            return;
        }
        long id;
        try {
            id = Long.parseLong(args[1]);
        } catch (NumberFormatException ex) {
            player.sendMessage("/stocks cancel <orderId>");
            return;
        }
        if (limitOrderService.cancel(player.getUniqueId(), id)) {
            player.sendMessage(messages.format("limit-cancelled", Map.of("id", String.valueOf(id))));
        } else {
            player.sendMessage(messages.format("limit-not-found", Map.of("id", String.valueOf(id))));
        }
    }

    private void showOrderBook(Player player, String[] args) throws SQLException {
        if (args.length < 2) {
            player.sendMessage("/stocks orderbook <symbol>");
            return;
        }
        MarketState state = marketService.state(args[1]).orElse(null);
        if (state == null) {
            player.sendMessage(messages.format("asset-not-found", Map.of("symbol", args[1].toUpperCase(Locale.ROOT))));
            return;
        }
        List<LimitOrder> orders = limitOrderService.openOrdersForSymbol(state.asset().symbol(), 12);
        if (orders.isEmpty()) {
            player.sendMessage("No open limit orders for " + state.asset().symbol() + ".");
            return;
        }
        player.sendMessage("Open orders for " + state.asset().symbol() + ":");
        for (LimitOrder order : orders) {
            player.sendMessage("#" + order.id() + " " + order.side()
                    + " " + Formats.quantity(order.quantity())
                    + " @ " + Formats.money(order.targetPrice()));
        }
    }

    private void showHistory(Player player, String[] args) throws SQLException {
        if (args.length < 2) {
            player.sendMessage("/stocks history <symbol>");
            return;
        }
        MarketState state = marketService.state(args[1]).orElse(null);
        if (state == null) {
            player.sendMessage(messages.format("asset-not-found", Map.of("symbol", args[1].toUpperCase(Locale.ROOT))));
            return;
        }
        List<dev.gabea.mcstocks.model.PricePoint> points = priceHistoryService.recent(state.asset().symbol(), 8);
        player.sendMessage(state.asset().symbol() + " history: " + priceHistoryService.sparkline(state.asset().symbol(), 16));
        for (dev.gabea.mcstocks.model.PricePoint point : points) {
            player.sendMessage(Formats.money(point.price()));
        }
    }

    public void sendTradeResult(Player player, TradeResult result) {
        Map<String, String> replacements = Map.of(
                "symbol", result.symbol(),
                "quantity", Formats.quantity(result.quantity()),
                "amount", Formats.money(result.total()),
                "total", Formats.money(result.total())
        );
        player.sendMessage(messages.format(result.messageKey(), replacements));
    }

    private List<String> filter(List<String> values, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> filtered = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                filtered.add(value);
            }
        }
        return filtered;
    }

    private String playerName(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString().substring(0, 8) : player.getName();
    }

    private String limitError(String key, String symbol, double quantity, double targetPrice) {
        double estimatedGross = quantity * targetPrice;
        double amount = switch (key == null ? "" : key) {
            case "trade-too-small" -> marketService.rules().minTradeValue();
            case "trade-too-large" -> marketService.rules().maxTradeValue();
            case "insufficient-funds" -> estimatedGross + (estimatedGross * (marketService.feePercent() / 100.0));
            default -> estimatedGross;
        };
        return messages.format(key == null ? "invalid-amount" : key, Map.of(
                "symbol", symbol.toUpperCase(Locale.ROOT),
                "amount", Formats.money(amount)
        ));
    }
}
