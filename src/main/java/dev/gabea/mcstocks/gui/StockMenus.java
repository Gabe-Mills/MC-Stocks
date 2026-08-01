package dev.gabea.mcstocks.gui;

import dev.gabea.mcstocks.MCStocksPlugin;
import dev.gabea.mcstocks.config.MessageService;
import dev.gabea.mcstocks.model.Holding;
import dev.gabea.mcstocks.model.LeaderboardEntry;
import dev.gabea.mcstocks.model.MarketState;
import dev.gabea.mcstocks.model.PlayerGainEntry;
import dev.gabea.mcstocks.model.TradeResult;
import dev.gabea.mcstocks.service.MarketService;
import dev.gabea.mcstocks.service.PortfolioService;
import dev.gabea.mcstocks.service.PriceHistoryService;
import dev.gabea.mcstocks.util.Formats;
import dev.gabea.mcstocks.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class StockMenus {
    private final MCStocksPlugin plugin;
    private final MessageService messages;
    private final MarketService marketService;
    private final PortfolioService portfolioService;
    private final PriceHistoryService priceHistoryService;

    public StockMenus(MCStocksPlugin plugin, MessageService messages, MarketService marketService, PortfolioService portfolioService, PriceHistoryService priceHistoryService) {
        this.plugin = plugin;
        this.messages = messages;
        this.marketService = marketService;
        this.portfolioService = portfolioService;
        this.priceHistoryService = priceHistoryService;
    }

    public void openMain(Player player) {
        StocksMenuHolder holder = new StocksMenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, 27, Text.component("&2MC Stocks"));
        holder.setInventory(inventory);

        inventory.setItem(10, item(Material.EMERALD, "&aMarket", List.of("&7Browse tradable stocks and coins.", "&eClick to open.")));
        holder.action(10, this::openMarket);

        inventory.setItem(12, item(Material.SPYGLASS, "&eMovers", List.of("&7See the strongest and weakest assets.", "&eClick to open.")));
        holder.action(12, this::openMovers);

        inventory.setItem(13, item(Material.CHEST, "&bPortfolio", List.of("&7View your holdings and value.", "&eClick to open.")));
        holder.action(13, this::openPortfolio);

        inventory.setItem(15, item(Material.WRITABLE_BOOK, "&dLimit Orders", List.of("&7Use &f/stocks orders&7 to list orders.", "&7Use &f/stocks limit buy IRON 1 100&7.")));

        inventory.setItem(16, item(Material.GOLD_INGOT, "&6Leaderboards", List.of("&7Top portfolio value, daily gains, and profit.", "&eClick to open.")));
        holder.action(16, this::openLeaderboards);

        inventory.setItem(22, item(marketService.isOpen() ? Material.LIME_DYE : Material.RED_DYE, marketService.isOpen() ? "&aMarket Open" : "&cMarket Paused", List.of(
                "&7Fee: &f" + Formats.rate(marketService.feePercent()),
                "&7Min trade: &f" + Formats.money(marketService.rules().minTradeValue()),
                "&7Max trade: &f" + Formats.money(marketService.rules().maxTradeValue())
        )));

        player.openInventory(inventory);
    }

    public void openMarket(Player player) {
        StocksMenuHolder holder = new StocksMenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, 54, Text.component("&2Market"));
        holder.setInventory(inventory);

        int slot = 0;
        for (MarketState state : marketService.allStates()) {
            if (slot >= 45) {
                break;
            }
            inventory.setItem(slot, marketItem(state));
            holder.action(slot, viewer -> openAsset(viewer, state.asset().symbol()));
            slot++;
        }

        inventory.setItem(48, item(Material.SPYGLASS, "&eMovers", List.of("&7Top market movement.")));
        holder.action(48, this::openMovers);
        inventory.setItem(49, item(Material.BARRIER, "&cBack", List.of()));
        holder.action(49, this::openMain);
        player.openInventory(inventory);
    }

    public void openMovers(Player player) {
        StocksMenuHolder holder = new StocksMenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, 54, Text.component("&2Market Movers"));
        holder.setInventory(inventory);

        List<MarketState> gainers = marketService.allStates().stream()
                .sorted(Comparator.comparingDouble(MarketState::changePercent).reversed())
                .limit(5)
                .toList();
        List<MarketState> losers = marketService.allStates().stream()
                .sorted(Comparator.comparingDouble(MarketState::changePercent))
                .limit(5)
                .toList();

        inventory.setItem(1, item(Material.LIME_CONCRETE, "&aTop Gainers", List.of()));
        fillMoverColumn(inventory, holder, 10, gainers);
        inventory.setItem(5, item(Material.RED_CONCRETE, "&cTop Losers", List.of()));
        fillMoverColumn(inventory, holder, 14, losers);

        inventory.setItem(49, item(Material.BARRIER, "&cBack", List.of()));
        holder.action(49, this::openMain);
        player.openInventory(inventory);
    }

    public void openAsset(Player player, String symbol) {
        MarketState state = marketService.state(symbol).orElse(null);
        if (state == null) {
            player.sendMessage(messages.format("asset-not-found", Map.of("symbol", symbol)));
            return;
        }

        StocksMenuHolder holder = new StocksMenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, 36, Text.component("&2" + state.asset().symbol() + " Trading"));
        holder.setInventory(inventory);

        inventory.setItem(4, marketItem(state));
        inventory.setItem(13, item(Material.PAPER, "&fTrade Rules", List.of(
                "&7Fee: &f" + Formats.rate(marketService.feePercent()),
                "&7Min value: &f" + Formats.money(marketService.rules().minTradeValue()),
                "&7Max value: &f" + Formats.money(marketService.rules().maxTradeValue()),
                state.asset().decimalTrading() ? "&7Units: &fDecimal" : "&7Units: &fWhole only",
                "&7Limit: &f/stocks limit buy " + state.asset().symbol() + " 1 " + Formats.quantity(state.price())
        )));

        List<Double> amounts = quickAmounts(state);
        int[] buySlots = {19, 20, 21};
        int[] sellSlots = {23, 24, 25};
        for (int index = 0; index < amounts.size() && index < buySlots.length; index++) {
            double quantity = amounts.get(index);
            inventory.setItem(buySlots[index], tradeItem(Material.LIME_CONCRETE, "&aBuy ", state, quantity, true));
            holder.action(buySlots[index], viewer -> quickTrade(viewer, symbol, quantity, true));
            inventory.setItem(sellSlots[index], tradeItem(Material.RED_CONCRETE, "&cSell ", state, quantity, false));
            holder.action(sellSlots[index], viewer -> quickTrade(viewer, symbol, quantity, false));
        }

        inventory.setItem(31, item(Material.BARRIER, "&cBack", List.of()));
        holder.action(31, this::openMarket);
        player.openInventory(inventory);
    }

    public void openPortfolio(Player player) {
        try {
            StocksMenuHolder holder = new StocksMenuHolder();
            Inventory inventory = Bukkit.createInventory(holder, 54, Text.component("&2Portfolio"));
            holder.setInventory(inventory);

            List<Holding> holdings = portfolioService.holdings(player.getUniqueId());
            int slot = 0;
            for (Holding holding : holdings) {
                if (slot >= 45) {
                    break;
                }
                MarketState state = marketService.state(holding.symbol()).orElse(null);
                Material material = state == null ? Material.PAPER : state.asset().displayMaterial();
                double price = state == null ? 0.0 : state.price();
                inventory.setItem(slot, item(material, "&a" + holding.symbol(), List.of(
                        "&7Quantity: &f" + Formats.quantity(holding.quantity()),
                        "&7Average cost: &f" + Formats.money(holding.averageCost()),
                        "&7Market value: &f" + Formats.money(holding.marketValue(price)),
                        "&eClick to trade."
                )));
                holder.action(slot, viewer -> openAsset(viewer, holding.symbol()));
                slot++;
            }

            inventory.setItem(49, item(Material.BARRIER, "&cBack", List.of("&7Total: " + Formats.money(portfolioService.portfolioValue(player.getUniqueId())))));
            holder.action(49, this::openMain);
            player.openInventory(inventory);
        } catch (SQLException ex) {
            plugin.getLogger().warning("Could not open portfolio menu: " + ex.getMessage());
            player.sendMessage("Database error. Check the server console.");
        }
    }

    public void openLeaderboards(Player player) {
        try {
            StocksMenuHolder holder = new StocksMenuHolder();
            Inventory inventory = Bukkit.createInventory(holder, 54, Text.component("&2Leaderboards"));
            holder.setInventory(inventory);

            int limit = Math.max(1, plugin.getConfig().getInt("leaderboards.size", 10));
            fillLeaderboard(inventory, 9, "&6Portfolio", portfolioService.topPortfolioValues(limit));
            fillDailyGainLeaderboard(inventory, 22, portfolioService.topDailyGains(limit, plugin.getConfig().getDouble("leaderboards.min-daily-gain-value", 1.0)));
            fillLeaderboard(inventory, 35, "&aProfit", portfolioService.topRealizedProfit(limit));

            inventory.setItem(49, item(Material.BARRIER, "&cBack", List.of()));
            holder.action(49, this::openMain);
            player.openInventory(inventory);
        } catch (SQLException ex) {
            plugin.getLogger().warning("Could not open leaderboard menu: " + ex.getMessage());
            player.sendMessage("Database error. Check the server console.");
        }
    }

    public void handleClickError(Player player, Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Menu click failed: " + ex.getMessage());
            player.sendMessage("Menu error. Check the server console.");
        }
    }

    private void quickTrade(Player player, String symbol, double quantity, boolean buy) {
        try {
            TradeResult result = buy
                    ? portfolioService.buy(player, symbol, quantity)
                    : portfolioService.sell(player, symbol, quantity);
            sendTradeResult(player, result);
            openAsset(player, symbol);
        } catch (SQLException ex) {
            plugin.getLogger().warning("Database error during menu trade: " + ex.getMessage());
            player.sendMessage("Database error. Check the server console.");
        }
    }

    private void sendTradeResult(Player player, TradeResult result) {
        player.sendMessage(messages.format(result.messageKey(), Map.of(
                "symbol", result.symbol(),
                "quantity", Formats.quantity(result.quantity()),
                "amount", Formats.money(result.total()),
                "total", Formats.money(result.total())
        )));
    }

    private ItemStack marketItem(MarketState state) {
        String changeColor = state.changePercent() >= 0.0 ? "&a" : "&c";
        String history = "no history yet";
        try {
            history = priceHistoryService.sparkline(state.asset().symbol(), 16);
        } catch (SQLException ex) {
            plugin.getLogger().warning("Could not load price history for " + state.asset().symbol() + ": " + ex.getMessage());
        }
        return item(state.asset().displayMaterial(), "&a" + state.asset().symbol() + " &7- &f" + state.asset().name(), List.of(
                "&7Type: &f" + state.asset().type(),
                "&7Price: &f" + Formats.money(state.price()),
                "&7Day: " + changeColor + Formats.percent(state.changePercent()),
                "&7History: &f" + history,
                "&7Status: " + (state.asset().enabled() ? "&aTradable" : "&cDisabled"),
                "&eClick to trade."
        ));
    }

    private ItemStack tradeItem(Material material, String prefix, MarketState state, double quantity, boolean buy) {
        double gross = state.price() * quantity;
        double fee = gross * (marketService.feePercent() / 100.0);
        double total = buy ? gross + fee : gross - fee;
        return item(material, prefix + Formats.quantity(quantity), List.of(
                "&7Price: &f" + Formats.money(state.price()),
                "&7Gross: &f" + Formats.money(gross),
                "&7Fee: &f" + Formats.money(fee),
                (buy ? "&7Cost: &f" : "&7Receive: &f") + Formats.money(total)
        ));
    }

    private List<Double> quickAmounts(MarketState state) {
        String path = state.asset().decimalTrading() ? "gui.crypto-quick-amounts" : "gui.stock-quick-amounts";
        List<Double> configured = plugin.getConfig().getDoubleList(path);
        if (configured.isEmpty()) {
            configured = state.asset().decimalTrading() ? List.of(0.1, 1.0, 10.0) : List.of(1.0, 10.0, 64.0);
        }
        return configured.stream()
                .filter(value -> value > 0.0)
                .limit(3)
                .toList();
    }

    private void fillMoverColumn(Inventory inventory, StocksMenuHolder holder, int startSlot, List<MarketState> states) {
        int slot = startSlot;
        for (MarketState state : states) {
            inventory.setItem(slot, marketItem(state));
            holder.action(slot, viewer -> openAsset(viewer, state.asset().symbol()));
            slot += 9;
        }
    }

    private void fillLeaderboard(Inventory inventory, int startSlot, String title, List<LeaderboardEntry> entries) {
        inventory.setItem(startSlot, item(Material.GOLD_BLOCK, title, List.of()));
        int slot = startSlot + 9;
        int rank = 1;
        for (LeaderboardEntry entry : entries) {
            if (rank > 4) {
                break;
            }
            OfflinePlayer player = Bukkit.getOfflinePlayer(entry.playerId());
            String name = playerName(player);
            inventory.setItem(slot, item(Material.PLAYER_HEAD, "&e#" + rank + " &f" + name, List.of("&7Value: &f" + Formats.money(entry.value()))));
            slot++;
            rank++;
        }
    }

    private void fillDailyGainLeaderboard(Inventory inventory, int startSlot, List<PlayerGainEntry> entries) {
        inventory.setItem(startSlot, item(Material.EMERALD_BLOCK, "&aDaily Gains", List.of()));
        int slot = startSlot + 9;
        int rank = 1;
        for (PlayerGainEntry entry : entries) {
            if (rank > 4) {
                break;
            }
            OfflinePlayer player = Bukkit.getOfflinePlayer(entry.playerId());
            String name = playerName(player);
            inventory.setItem(slot, item(Material.PLAYER_HEAD, "&e#" + rank + " &f" + name, List.of(
                    "&7Gain: &f" + Formats.percent(entry.gainPercent()),
                    "&7Start: &f" + Formats.money(entry.startValue()),
                    "&7Now: &f" + Formats.money(entry.currentValue())
            )));
            slot++;
            rank++;
        }
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.component(name));
            List<Component> coloredLore = new ArrayList<>();
            for (String line : lore) {
                coloredLore.add(Text.component(line));
            }
            meta.lore(coloredLore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private String playerName(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString().substring(0, 8) : player.getName();
    }
}
