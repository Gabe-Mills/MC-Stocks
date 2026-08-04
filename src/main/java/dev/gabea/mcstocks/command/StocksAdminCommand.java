package dev.gabea.mcstocks.command;

import dev.gabea.mcstocks.MCStocksPlugin;
import dev.gabea.mcstocks.config.MessageService;
import dev.gabea.mcstocks.model.Holding;
import dev.gabea.mcstocks.model.TradeResult;
import dev.gabea.mcstocks.service.MarketService;
import dev.gabea.mcstocks.service.PortfolioService;
import dev.gabea.mcstocks.util.Formats;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class StocksAdminCommand implements CommandExecutor, TabCompleter {
    private final MCStocksPlugin plugin;
    private final MessageService messages;
    private final MarketService marketService;
    private final PortfolioService portfolioService;

    public StocksAdminCommand(MCStocksPlugin plugin, MessageService messages, MarketService marketService, PortfolioService portfolioService) {
        this.plugin = plugin;
        this.messages = messages;
        this.marketService = marketService;
        this.portfolioService = portfolioService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("/stocksadmin <reload|pause|resume|setprice|resetprice|freeze|unfreeze|event|inspect|reverse|backup>");
            return true;
        }

        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "reload" -> reload(sender);
                case "pause" -> pause(sender);
                case "resume" -> resume(sender);
                case "setprice" -> setPrice(sender, args);
                case "resetprice" -> resetPrice(sender, args);
                case "freeze" -> freeze(sender, args, true);
                case "unfreeze" -> freeze(sender, args, false);
                case "event" -> event(sender, args);
                case "inspect" -> inspect(sender, args);
                case "reverse" -> reverse(sender, args);
                case "backup" -> backup(sender);
                default -> sender.sendMessage("/stocksadmin <reload|pause|resume|setprice|resetprice|freeze|unfreeze|event|inspect|reverse|backup>");
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Database error during /stocksadmin: " + ex.getMessage());
            sender.sendMessage("Database error. Check the server console.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("reload", "pause", "resume", "setprice", "resetprice", "freeze", "unfreeze", "event", "inspect", "reverse", "backup"), args[0]);
        }
        if (args.length == 2 && List.of("setprice", "resetprice", "freeze", "unfreeze").contains(args[0].toLowerCase(Locale.ROOT))) {
            List<String> values = new ArrayList<>(symbols());
            if ("resetprice".equalsIgnoreCase(args[0])) {
                values.add("all");
            }
            return filter(values, args[1]);
        }
        if (args.length == 2 && "event".equalsIgnoreCase(args[0])) {
            List<String> values = new ArrayList<>(symbols());
            values.add("all");
            return filter(values, args[1]);
        }
        if (args.length == 3 && "event".equalsIgnoreCase(args[0])) {
            return filter(List.of("bull", "bear", "crash", "pump"), args[2]);
        }
        return List.of();
    }

    private void reload(CommandSender sender) {
        if (!has(sender, "stocks.admin.reload")) {
            return;
        }
        plugin.reloadPluginConfig();
        plugin.auditService().log(sender.getName(), "RELOAD", "config reloaded");
        sender.sendMessage(messages.get("reloaded"));
    }

    private void pause(CommandSender sender) {
        if (!has(sender, "stocks.admin.pause")) {
            return;
        }
        marketService.pause();
        plugin.auditService().log(sender.getName(), "PAUSE", "market paused");
        sender.sendMessage(messages.get("market-paused"));
    }

    private void resume(CommandSender sender) {
        if (!has(sender, "stocks.admin.pause")) {
            return;
        }
        marketService.resume();
        plugin.auditService().log(sender.getName(), "RESUME", "market resumed");
        sender.sendMessage(messages.get("market-resumed"));
    }

    private void setPrice(CommandSender sender, String[] args) {
        if (!has(sender, "stocks.admin.setprice")) {
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("/stocksadmin setprice <symbol> <price>");
            return;
        }
        double price;
        try {
            price = Double.parseDouble(args[2]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("Enter a valid price.");
            return;
        }
        if (price <= 0.0 || marketService.state(args[1]).isEmpty()) {
            sender.sendMessage(messages.format("asset-not-found", Map.of("symbol", args[1].toUpperCase(Locale.ROOT))));
            return;
        }
        marketService.setPrice(args[1], price);
        plugin.auditService().log(sender.getName(), "SETPRICE", args[1].toUpperCase(Locale.ROOT) + "=" + price);
        sender.sendMessage("Set " + args[1].toUpperCase(Locale.ROOT) + " to " + Formats.money(price));
    }

    private void resetPrice(CommandSender sender, String[] args) {
        if (!has(sender, "stocks.admin.reset")) {
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("/stocksadmin resetprice <symbol|all>");
            return;
        }
        if ("all".equalsIgnoreCase(args[1])) {
            marketService.resetAllPrices();
            plugin.auditService().log(sender.getName(), "RESETPRICE", "all");
            sender.sendMessage("Reset all asset prices to their configured initial values.");
            return;
        }
        if (marketService.state(args[1]).isEmpty()) {
            sender.sendMessage(messages.format("asset-not-found", Map.of("symbol", args[1].toUpperCase(Locale.ROOT))));
            return;
        }
        marketService.resetPrice(args[1]);
        plugin.auditService().log(sender.getName(), "RESETPRICE", args[1].toUpperCase(Locale.ROOT));
        sender.sendMessage("Reset " + args[1].toUpperCase(Locale.ROOT) + " to its initial price.");
    }

    private void freeze(CommandSender sender, String[] args, boolean frozen) {
        if (!has(sender, "stocks.admin.freeze")) {
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("/stocksadmin " + (frozen ? "freeze" : "unfreeze") + " <symbol>");
            return;
        }
        if (!marketService.freeze(args[1], frozen)) {
            sender.sendMessage(messages.format("asset-not-found", Map.of("symbol", args[1].toUpperCase(Locale.ROOT))));
            return;
        }
        plugin.auditService().log(sender.getName(), frozen ? "FREEZE" : "UNFREEZE", args[1].toUpperCase(Locale.ROOT));
        sender.sendMessage(messages.format(frozen ? "asset-frozen-admin" : "asset-unfrozen-admin", Map.of("symbol", args[1].toUpperCase(Locale.ROOT))));
    }

    private void event(CommandSender sender, String[] args) {
        if (!has(sender, "stocks.admin.event")) {
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("/stocksadmin event <symbol|all> <bull|bear|crash|pump>");
            return;
        }
        if (!List.of("bull", "bear", "crash", "pump").contains(args[2].toLowerCase(Locale.ROOT))) {
            sender.sendMessage("/stocksadmin event <symbol|all> <bull|bear|crash|pump>");
            return;
        }
        if (!"all".equalsIgnoreCase(args[1]) && marketService.state(args[1]).isEmpty()) {
            sender.sendMessage(messages.format("asset-not-found", Map.of("symbol", args[1].toUpperCase(Locale.ROOT))));
            return;
        }
        marketService.event(args[1], args[2]);
        plugin.auditService().log(sender.getName(), "EVENT", args[1].toUpperCase(Locale.ROOT) + " " + args[2].toLowerCase(Locale.ROOT));
        sender.sendMessage("Triggered " + args[2].toLowerCase(Locale.ROOT) + " event for " + args[1].toUpperCase(Locale.ROOT) + ".");
    }

    private void inspect(CommandSender sender, String[] args) throws SQLException {
        if (!has(sender, "stocks.admin.inspect")) {
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("/stocksadmin inspect <player>");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        sender.sendMessage("Portfolio for " + target.getName() + ": " + Formats.money(portfolioService.portfolioValue(target.getUniqueId())));
        for (Holding holding : portfolioService.holdings(target.getUniqueId())) {
            sender.sendMessage(holding.symbol() + ": " + Formats.quantity(holding.quantity()) + " avg " + Formats.money(holding.averageCost()));
        }
    }

    private void reverse(CommandSender sender, String[] args) throws SQLException {
        if (!has(sender, "stocks.admin.reverse")) {
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("/stocksadmin reverse <tradeId>");
            return;
        }
        long tradeId;
        try {
            tradeId = Long.parseLong(args[1]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("/stocksadmin reverse <tradeId>");
            return;
        }
        TradeResult result = portfolioService.reverseTrade(sender.getName(), tradeId);
        if (result.success()) {
            sender.sendMessage(messages.format("trade-reversed", Map.of(
                    "symbol", result.symbol(),
                    "quantity", Formats.quantity(result.quantity()),
                    "amount", Formats.money(result.total())
            )));
        } else {
            sender.sendMessage(messages.format(result.messageKey(), Map.of(
                    "symbol", result.symbol() == null ? "" : result.symbol(),
                    "amount", Formats.money(result.total())
            )));
        }
    }

    private void backup(CommandSender sender) {
        if (!has(sender, "stocks.admin")) {
            return;
        }
        plugin.backupService().backupNow();
        plugin.auditService().log(sender.getName(), "BACKUP", "manual backup requested");
        sender.sendMessage(messages.get("backup-started"));
    }

    private boolean has(CommandSender sender, String permission) {
        if (sender.hasPermission(permission) || sender.hasPermission("stocks.admin") || sender.hasPermission("mcstocks.admin")) {
            return true;
        }
        sender.sendMessage("Missing permission.");
        return false;
    }

    private List<String> symbols() {
        return marketService.allStates().stream().map(state -> state.asset().symbol()).toList();
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
}
