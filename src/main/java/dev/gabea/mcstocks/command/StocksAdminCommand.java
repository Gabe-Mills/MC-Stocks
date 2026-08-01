package dev.gabea.mcstocks.command;

import dev.gabea.mcstocks.MCStocksPlugin;
import dev.gabea.mcstocks.config.MessageService;
import dev.gabea.mcstocks.model.Holding;
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
            sender.sendMessage("/stocksadmin <reload|pause|resume|setprice|event|inspect>");
            return true;
        }

        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "reload" -> reload(sender);
                case "pause" -> pause(sender);
                case "resume" -> resume(sender);
                case "setprice" -> setPrice(sender, args);
                case "event" -> event(sender, args);
                case "inspect" -> inspect(sender, args);
                default -> sender.sendMessage("/stocksadmin <reload|pause|resume|setprice|event|inspect>");
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
            return filter(List.of("reload", "pause", "resume", "setprice", "event", "inspect"), args[0]);
        }
        if (args.length == 2 && "setprice".equalsIgnoreCase(args[0])) {
            return filter(symbols(), args[1]);
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
        if (!sender.hasPermission("mcstocks.admin.reload")) {
            sender.sendMessage("Missing permission.");
            return;
        }
        plugin.reloadPluginConfig();
        sender.sendMessage(messages.get("reloaded"));
    }

    private void pause(CommandSender sender) {
        if (!sender.hasPermission("mcstocks.admin.pause")) {
            sender.sendMessage("Missing permission.");
            return;
        }
        marketService.pause();
        sender.sendMessage(messages.get("market-paused"));
    }

    private void resume(CommandSender sender) {
        if (!sender.hasPermission("mcstocks.admin.pause")) {
            sender.sendMessage("Missing permission.");
            return;
        }
        marketService.resume();
        sender.sendMessage(messages.get("market-resumed"));
    }

    private void setPrice(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mcstocks.admin.setprice")) {
            sender.sendMessage("Missing permission.");
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
        sender.sendMessage("Set " + args[1].toUpperCase(Locale.ROOT) + " to " + Formats.money(price));
    }

    private void event(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mcstocks.admin.event")) {
            sender.sendMessage("Missing permission.");
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
        sender.sendMessage("Triggered " + args[2].toLowerCase(Locale.ROOT) + " event for " + args[1].toUpperCase(Locale.ROOT) + ".");
    }

    private void inspect(CommandSender sender, String[] args) throws SQLException {
        if (!sender.hasPermission("mcstocks.admin.inspect")) {
            sender.sendMessage("Missing permission.");
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
