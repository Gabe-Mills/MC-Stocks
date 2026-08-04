package dev.gabea.mcstocks.service;

import dev.gabea.mcstocks.config.AssetRegistry;
import dev.gabea.mcstocks.model.Asset;
import dev.gabea.mcstocks.model.MarketRules;
import dev.gabea.mcstocks.model.MarketState;
import dev.gabea.mcstocks.storage.Database;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

public final class MarketService {
    private final AssetRegistry assetRegistry;
    private final Database database;
    private final Random random = new Random();
    private final Map<String, MarketState> states = new LinkedHashMap<>();
    private boolean open;
    private boolean hoursEnabled;
    private ZoneId timezone = ZoneId.systemDefault();
    private LocalTime openTime = LocalTime.of(9, 30);
    private LocalTime closeTime = LocalTime.of(16, 0);
    private Set<DayOfWeek> openDays = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
    private int ticksIntoDay;
    private int ticksPerDay;
    private double maxChangePerTick;
    private boolean circuitBreakerEnabled;
    private double circuitBreakerTripPercent;
    private MarketRules rules;

    public MarketService(AssetRegistry assetRegistry, Database database, FileConfiguration config) {
        this.assetRegistry = assetRegistry;
        this.database = database;
        reload(config);
    }

    public void reload(FileConfiguration config) {
        open = config.getBoolean("market.open-on-start", true);
        int dayMinutes = Math.max(1, config.getInt("market.day-minutes", 30));
        int tickSeconds = Math.max(1, config.getInt("market.tick-seconds", 60));
        ticksPerDay = Math.max(1, (dayMinutes * 60) / tickSeconds);
        maxChangePerTick = Math.max(0.01, config.getDouble("market.max-price-change-percent-per-tick", 12.0)) / 100.0;
        circuitBreakerEnabled = config.getBoolean("circuit-breaker.enabled", true);
        circuitBreakerTripPercent = Math.max(1.0, config.getDouble("circuit-breaker.trip-percent", 25.0));
        rules = new MarketRules(
                Math.max(0.0, config.getDouble("market.transaction-fee-percent", 1.0)),
                Math.max(0.0, config.getDouble("market.tax-percent", 0.0)),
                Math.max(0.0, config.getDouble("market.min-trade-value", 1.0)),
                Math.max(0.01, config.getDouble("market.max-trade-value", 100000.0))
        );

        ConfigurationSection hours = config.getConfigurationSection("market.hours");
        hoursEnabled = hours != null && hours.getBoolean("enabled", false);
        if (hours != null) {
            try {
                timezone = ZoneId.of(hours.getString("timezone", ZoneId.systemDefault().getId()));
            } catch (Exception ignored) {
                timezone = ZoneId.systemDefault();
            }
            openTime = parseTime(hours.getString("open", "09:30"), LocalTime.of(9, 30));
            closeTime = parseTime(hours.getString("close", "16:00"), LocalTime.of(16, 0));
            openDays = EnumSet.noneOf(DayOfWeek.class);
            for (String day : hours.getStringList("days")) {
                try {
                    openDays.add(DayOfWeek.valueOf(day.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (openDays.isEmpty()) {
                openDays = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
            }
        }

        Map<String, MarketState> oldStates = new LinkedHashMap<>(states);
        states.clear();
        for (Asset asset : assetRegistry.all()) {
            MarketState previous = oldStates.get(asset.symbol());
            MarketState state = new MarketState(asset);
            if (previous != null) {
                state.restore(previous.price(), previous.openPrice(), previous.previousPrice(), previous.frozen());
            }
            states.put(asset.symbol(), state);
        }
    }

    public void loadPersistedState() throws SQLException {
        database.sync(() -> {
            try (PreparedStatement statement = database.connection().prepareStatement(
                    "SELECT symbol, price, open_price, previous_price, frozen FROM market_state");
                 ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    String symbol = results.getString("symbol");
                    MarketState state = states.get(symbol);
                    if (state == null) {
                        continue;
                    }
                    state.restore(
                            results.getDouble("price"),
                            results.getDouble("open_price"),
                            results.getDouble("previous_price"),
                            results.getInt("frozen") != 0
                    );
                }
            }
            String unclean = database.getMeta("unclean_shutdown", "0");
            if ("1".equals(unclean)) {
                database.setMeta("recovered_from_crash", Instant.now().toString());
            }
            database.setMeta("unclean_shutdown", "1");
            return null;
        });
    }

    public void persistState() throws SQLException {
        database.sync(() -> {
            long now = Instant.now().toEpochMilli();
            try (PreparedStatement statement = database.connection().prepareStatement(database.upsertMarketStateSql())) {
                for (MarketState state : states.values()) {
                    statement.setString(1, state.asset().symbol());
                    statement.setDouble(2, state.price());
                    statement.setDouble(3, state.openPrice());
                    statement.setDouble(4, state.previousPrice());
                    statement.setInt(5, state.frozen() ? 1 : 0);
                    statement.setLong(6, now);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            database.setMeta("market_open", open ? "1" : "0");
            database.setMeta("unclean_shutdown", "0");
            return null;
        });
    }

    public void tick() {
        if (!isOpen()) {
            return;
        }

        ticksIntoDay++;
        if (ticksIntoDay >= ticksPerDay) {
            ticksIntoDay = 0;
            states.values().forEach(MarketState::resetOpenPrice);
        }

        for (MarketState state : states.values()) {
            Asset asset = state.asset();
            if (!asset.enabled() || state.frozen()) {
                continue;
            }
            double movement = asset.trend() + (random.nextGaussian() * asset.volatility());
            movement = Math.max(-maxChangePerTick, Math.min(maxChangePerTick, movement));
            state.setPrice(roundCurrency(state.price() * (1.0 + movement)));
            if (circuitBreakerEnabled && Math.abs(state.changePercent()) >= circuitBreakerTripPercent) {
                state.setFrozen(true);
            }
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
        return open && withinConfiguredHours();
    }

    public boolean manuallyOpen() {
        return open;
    }

    public boolean withinConfiguredHours() {
        if (!hoursEnabled) {
            return true;
        }
        ZonedDateTime now = ZonedDateTime.now(timezone);
        if (!openDays.contains(now.getDayOfWeek())) {
            return false;
        }
        LocalTime time = now.toLocalTime();
        if (openTime.equals(closeTime)) {
            return true;
        }
        if (openTime.isBefore(closeTime)) {
            return !time.isBefore(openTime) && time.isBefore(closeTime);
        }
        return !time.isBefore(openTime) || time.isBefore(closeTime);
    }

    public void pause() {
        open = false;
    }

    public void resume() {
        open = true;
    }

    public void setPrice(String symbol, double price) {
        state(symbol).ifPresent(marketState -> marketState.setPrice(roundCurrency(price)));
    }

    public void resetPrice(String symbol) {
        state(symbol).ifPresent(MarketState::resetToInitial);
    }

    public void resetAllPrices() {
        states.values().forEach(MarketState::resetToInitial);
    }

    public boolean freeze(String symbol, boolean frozen) {
        Optional<MarketState> state = state(symbol);
        state.ifPresent(marketState -> marketState.setFrozen(frozen));
        return state.isPresent();
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
            states.values().forEach(state -> {
                if (!state.frozen()) {
                    state.setPrice(roundCurrency(state.price() * multiplier));
                }
            });
            return;
        }
        state(target).ifPresent(state -> {
            if (!state.frozen()) {
                state.setPrice(roundCurrency(state.price() * multiplier));
            }
        });
    }

    public double feePercent() {
        return rules.feePercent();
    }

    public double taxPercent() {
        return rules.taxPercent();
    }

    public MarketRules rules() {
        return rules;
    }

    private LocalTime parseTime(String raw, LocalTime fallback) {
        try {
            return LocalTime.parse(raw);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private double roundCurrency(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
