package dev.gabea.mcstocks;

import dev.gabea.mcstocks.command.StocksAdminCommand;
import dev.gabea.mcstocks.command.StocksCommand;
import dev.gabea.mcstocks.config.AssetRegistry;
import dev.gabea.mcstocks.config.MessageService;
import dev.gabea.mcstocks.gui.StockMenus;
import dev.gabea.mcstocks.gui.StocksMenuListener;
import dev.gabea.mcstocks.hook.StocksPlaceholders;
import dev.gabea.mcstocks.service.AuditService;
import dev.gabea.mcstocks.service.BackupService;
import dev.gabea.mcstocks.service.EconomyService;
import dev.gabea.mcstocks.service.LimitOrderService;
import dev.gabea.mcstocks.service.MarketService;
import dev.gabea.mcstocks.service.PortfolioService;
import dev.gabea.mcstocks.service.PriceHistoryService;
import dev.gabea.mcstocks.service.TradeLockService;
import dev.gabea.mcstocks.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class MCStocksPlugin extends JavaPlugin {
    private MessageService messages;
    private AssetRegistry assetRegistry;
    private EconomyService economyService;
    private MarketService marketService;
    private PortfolioService portfolioService;
    private PriceHistoryService priceHistoryService;
    private LimitOrderService limitOrderService;
    private BackupService backupService;
    private AuditService auditService;
    private StockMenus stockMenus;
    private Database database;
    private TradeLockService tradeLocks;
    private ScheduledExecutorService marketScheduler;
    private ScheduledFuture<?> marketFuture;
    private ScheduledFuture<?> backupFuture;
    private StocksPlaceholders placeholders;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new MessageService(this);
        assetRegistry = new AssetRegistry(this);
        assetRegistry.reload();
        tradeLocks = new TradeLockService();

        economyService = new EconomyService(this);
        if (!economyService.hook()) {
            getLogger().severe("Vault economy provider was not found. Install Vault plus EssentialsX Economy (or any Vault economy).");
        }

        try {
            database = new Database(this);
        } catch (SQLException ex) {
            getLogger().severe("Could not open database. Disabling MC-Stocks.");
            ex.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        auditService = new AuditService(database);
        marketService = new MarketService(assetRegistry, database, getConfig());
        try {
            marketService.loadPersistedState();
        } catch (SQLException ex) {
            getLogger().warning("Could not restore market state after previous run: " + ex.getMessage());
        }

        priceHistoryService = new PriceHistoryService(database, getConfig().getInt("market.price-history-limit-per-asset", 500));
        portfolioService = new PortfolioService(database, marketService, economyService, tradeLocks, auditService);
        limitOrderService = new LimitOrderService(
                database,
                marketService,
                portfolioService,
                economyService,
                messages,
                getConfig().getBoolean("limit-orders.enabled", true),
                getConfig().getInt("limit-orders.max-open-per-player", 10),
                getConfig().getInt("limit-orders.stale-processing-seconds", 300)
        );
        backupService = new BackupService(this, database, getConfig());
        stockMenus = new StockMenus(this, messages, marketService, portfolioService, priceHistoryService);
        getServer().getPluginManager().registerEvents(new StocksMenuListener(stockMenus), this);

        registerCommands();
        recordInitialHistory();
        startSchedulers();
        hookPlaceholders();
        getLogger().info("MC-Stocks enabled with " + assetRegistry.all().size() + " configured assets (" + database.type() + ").");
    }

    @Override
    public void onDisable() {
        stopSchedulers();
        if (placeholders != null) {
            placeholders.unregister();
            placeholders = null;
        }
        if (marketService != null && database != null) {
            try {
                marketService.persistState();
            } catch (SQLException ex) {
                getLogger().warning("Could not persist market state on shutdown: " + ex.getMessage());
            }
        }
        if (database != null) {
            try {
                database.close();
            } catch (SQLException ex) {
                getLogger().warning("Could not close database cleanly: " + ex.getMessage());
            }
        }
    }

    public void reloadPluginConfig() {
        reloadConfig();
        messages.reload();
        assetRegistry.reload();
        marketService.reload(getConfig());
        priceHistoryService.reload(getConfig().getInt("market.price-history-limit-per-asset", 500));
        limitOrderService.reload(
                getConfig().getBoolean("limit-orders.enabled", true),
                getConfig().getInt("limit-orders.max-open-per-player", 10),
                getConfig().getInt("limit-orders.stale-processing-seconds", 300)
        );
        backupService.reload(getConfig());
        startSchedulers();
    }

    public MessageService messages() {
        return messages;
    }

    public MarketService marketService() {
        return marketService;
    }

    public PortfolioService portfolioService() {
        return portfolioService;
    }

    public PriceHistoryService priceHistoryService() {
        return priceHistoryService;
    }

    public LimitOrderService limitOrderService() {
        return limitOrderService;
    }

    public AuditService auditService() {
        return auditService;
    }

    public BackupService backupService() {
        return backupService;
    }

    public StockMenus stockMenus() {
        return stockMenus;
    }

    private void registerCommands() {
        StocksCommand stocksCommand = new StocksCommand(this, messages, marketService, portfolioService, priceHistoryService, limitOrderService, stockMenus);
        PluginCommand stocks = getCommand("stocks");
        if (stocks != null) {
            stocks.setExecutor(stocksCommand);
            stocks.setTabCompleter(stocksCommand);
        }

        StocksAdminCommand adminCommand = new StocksAdminCommand(this, messages, marketService, portfolioService);
        PluginCommand stocksAdmin = getCommand("stocksadmin");
        if (stocksAdmin != null) {
            stocksAdmin.setExecutor(adminCommand);
            stocksAdmin.setTabCompleter(adminCommand);
        }
    }

    private void startSchedulers() {
        stopSchedulers();
        marketScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MC-Stocks-Market");
            thread.setDaemon(true);
            return thread;
        });

        long tickSeconds = Math.max(1L, getConfig().getLong("market.tick-seconds", 60));
        marketFuture = marketScheduler.scheduleAtFixedRate(
                () -> Bukkit.getScheduler().runTask(this, this::runMarketTick),
                tickSeconds,
                tickSeconds,
                TimeUnit.SECONDS
        );

        if (backupService.enabled()) {
            long intervalMinutes = Math.max(5L, getConfig().getLong("backups.interval-minutes", 60));
            backupFuture = marketScheduler.scheduleAtFixedRate(
                    () -> Bukkit.getScheduler().runTaskAsynchronously(this, backupService::backupNow),
                    intervalMinutes,
                    intervalMinutes,
                    TimeUnit.MINUTES
            );
        }
    }

    private void stopSchedulers() {
        if (marketFuture != null) {
            marketFuture.cancel(false);
            marketFuture = null;
        }
        if (backupFuture != null) {
            backupFuture.cancel(false);
            backupFuture = null;
        }
        if (marketScheduler != null) {
            marketScheduler.shutdownNow();
            marketScheduler = null;
        }
    }

    private void runMarketTick() {
        try {
            marketService.tick();
            if (marketService.isOpen()) {
                priceHistoryService.recordAsync(marketService.allStates());
                limitOrderService.processOpenOrders();
            }
            marketService.persistState();
        } catch (SQLException ex) {
            getLogger().warning("Market tick failed: " + ex.getMessage());
        }
    }

    private void recordInitialHistory() {
        priceHistoryService.recordAsync(marketService.allStates());
    }

    private void hookPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        placeholders = new StocksPlaceholders(this);
        if (placeholders.register()) {
            getLogger().info("Hooked PlaceholderAPI expansion %mcstocks_%.");
        }
    }
}
