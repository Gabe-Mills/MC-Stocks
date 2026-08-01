package dev.gabea.mcstocks.service;

import dev.gabea.mcstocks.config.AssetRegistry;
import dev.gabea.mcstocks.model.Asset;
import dev.gabea.mcstocks.model.MarketRules;
import dev.gabea.mcstocks.model.MarketState;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

public final class MarketService {
    private final AssetRegistry assetRegistry;
    private final Random random = new Random();
    private final Map<String, MarketState> states = new LinkedHashMap<>();
    private boolean open;
    private int ticksIntoDay;
    private int ticksPerDay;
    private double maxChangePerTick;
    private MarketRules rules;

    public MarketService(AssetRegistry assetRegistry, FileConfiguration config) {
        this.assetRegistry = assetRegistry;
        reload(config);
    }

    public void reload(FileConfiguration config) {
        open = config.getBoolean("market.open-on-start", true);
        int dayMinutes = Math.max(1, config.getInt("market.day-minutes", 30));
        int tickSeconds = Math.max(1, config.getInt("market.tick-seconds", 60));
        ticksPerDay = Math.max(1, (dayMinutes * 60) / tickSeconds);
        maxChangePerTick = Math.max(0.01, config.getDouble("market.max-price-change-percent-per-tick", 12.0)) / 100.0;
        rules = new MarketRules(
                Math.max(0.0, config.getDouble("market.transaction-fee-percent", 1.0)),
                Math.max(0.0, config.getDouble("market.min-trade-value", 1.0)),
                Math.max(0.01, config.getDouble("market.max-trade-value", 100000.0))
        );

        Map<String, MarketState> oldStates = new LinkedHashMap<>(states);
        states.clear();
        for (Asset asset : assetRegistry.all()) {
            MarketState previous = oldStates.get(asset.symbol());
            MarketState state = new MarketState(asset);
            if (previous != null) {
                state.restore(previous.price(), previous.openPrice(), previous.previousPrice());
            }
            states.put(asset.symbol(), state);
        }
    }

    public void tick() {
        if (!open) {
            return;
        }
        ticksIntoDay++;
        if (ticksIntoDay >= ticksPerDay) {
            ticksIntoDay = 0;
            states.values().forEach(MarketState::resetOpenPrice);
        }

        for (MarketState state : states.values()) {
            Asset asset = state.asset();
            if (!asset.enabled()) {
                continue;
            }
            double movement = asset.trend() + (random.nextGaussian() * asset.volatility());
            movement = Math.max(-maxChangePerTick, Math.min(maxChangePerTick, movement));
            state.setPrice(roundCurrency(state.price() * (1.0 + movement)));
        }
    }

    public Optional<MarketState> state(String symbol) {
        if (symbol == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(states.get(symbol.toUpperCase(Locale.ROOT)));
    }

    public Collection<MarketState> allStates() {
        return states.values();
    }

    public boolean isOpen() {
        return open;
    }

    public void pause() {
        open = false;
    }

    public void resume() {
        open = true;
    }

    public void setPrice(String symbol, double price) {
        state(symbol).ifPresent(state -> state.setPrice(roundCurrency(price)));
    }

    public void event(String target, String event) {
        double multiplier = switch (event.toLowerCase(Locale.ROOT)) {
            case "bull" -> 1.08;
            case "bear" -> 0.92;
            case "crash" -> 0.72;
            case "pump" -> 1.25;
            default -> 1.0;
        };
        if ("all".equalsIgnoreCase(target)) {
            states.values().forEach(state -> state.setPrice(roundCurrency(state.price() * multiplier)));
            return;
        }
        state(target).ifPresent(state -> state.setPrice(roundCurrency(state.price() * multiplier)));
    }

    public double feePercent() {
        return rules.feePercent();
    }

    public MarketRules rules() {
        return rules;
    }

    private double roundCurrency(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
