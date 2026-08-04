package dev.gabea.mcstocks.hook;

import dev.gabea.mcstocks.MCStocksPlugin;
import dev.gabea.mcstocks.model.Holding;
import dev.gabea.mcstocks.model.MarketState;
import dev.gabea.mcstocks.util.Formats;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.sql.SQLException;
import java.util.Locale;

public final class StocksPlaceholders extends PlaceholderExpansion {
    private final MCStocksPlugin plugin;

    public StocksPlaceholders(MCStocksPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "mcstocks";
    }

    @Override
    public String getAuthor() {
        return "Gabe";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String key = params.toLowerCase(Locale.ROOT);
        if (key.equals("market_status")) {
            return plugin.marketService().isOpen() ? "OPEN" : "CLOSED";
        }
        if (key.equals("market_open")) {
            return plugin.marketService().isOpen() ? "true" : "false";
        }
        if (key.startsWith("price_")) {
            return plugin.marketService().state(key.substring("price_".length()))
                    .map(state -> Formats.money(state.price()))
                    .orElse("");
        }
        if (key.startsWith("change_")) {
            return plugin.marketService().state(key.substring("change_".length()))
                    .map(state -> Formats.percent(state.changePercent()))
                    .orElse("");
        }
        if (key.startsWith("frozen_")) {
            return plugin.marketService().state(key.substring("frozen_".length()))
                    .map(state -> state.frozen() ? "true" : "false")
                    .orElse("");
        }
        if (player == null) {
            return "";
        }
        try {
            if (key.equals("portfolio_value")) {
                return Formats.money(plugin.portfolioService().portfolioValue(player.getUniqueId()));
            }
            if (key.startsWith("holding_value_")) {
                String symbol = key.substring("holding_value_".length()).toUpperCase(Locale.ROOT);
                Holding holding = plugin.portfolioService().holding(player.getUniqueId(), symbol);
                double price = plugin.marketService().state(symbol).map(MarketState::price).orElse(0.0);
                return Formats.money(holding.marketValue(price));
            }
            if (key.startsWith("holding_")) {
                String symbol = key.substring("holding_".length()).toUpperCase(Locale.ROOT);
                Holding holding = plugin.portfolioService().holding(player.getUniqueId(), symbol);
                return Formats.quantity(holding.quantity());
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Placeholder lookup failed: " + ex.getMessage());
            return "";
        }
        return null;
    }
}
