package dev.gabea.mcstocks;

import dev.gabea.mcstocks.command.StocksAdminCommand;
import dev.gabea.mcstocks.command.StocksCommand;
import dev.gabea.mcstocks.config.AssetRegistry;
import dev.gabea.mcstocks.config.MessageService;
import dev.gabea.mcstocks.gui.StockMenus;
import dev.gabea.mcstocks.gui.StocksMenuListener;
import dev.gabea.mcstocks.service.EconomyService;
import dev.gabea.mcstocks.service.LimitOrderService;
import dev.gabea.mcstocks.service.MarketService;
import dev.gabea.mcstocks.service.PortfolioService;
import dev.gabea.mcstocks.service.PriceHistoryService;
import dev.gabea.mcstocks.storage.Database;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;

public final class MCStocksPlugin extends JavaPlugin {
    private MessageService messages;
    private AssetRegistry assetRegistry;
    private EconomyService economyService;
    private MarketService marketService;
    private PortfolioService portfolioService;
    private PriceHistoryService priceHistoryService;
    private LimitOrderService limitOrderService;
    private StockMenus stockMenus;
    private Database database;
    private BukkitTask marketTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new MessageService(this);
        assetRegistry = new AssetRegistry(this);
        assetRegistry.reload();

        economyService = new EconomyService(this);
        if (!economyService.hook()) {
            getLogger().severe("Vault economy provider was not found. Install Vault and EssentialsX Economy.");
        }

        try {
            database = new Database(this);
        } catch (SQLException ex) {
            getLogger().severe("Could not open SQLite database. Disabling MC-Stocks.");
            ex.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        marketService = new MarketService(assetRegistry, getConfig());
        priceHistoryService = new PriceHistoryService(database, getConfig().getInt("market.price-history-limit-per-asset", 500));
        portfolioService = new PortfolioService(database, marketService, economyService);
        limitOrderService = new LimitOrderService(database, marketService, portfolioService, economyService, messages, getConfig().getBoolean("limit-orders.enabled", true), getConfig().getInt("limit-orders.max-open-per-player", 10), getConfig().getInt("limit-orders.stale-processing-seconds", 300));
        stockMenus = new StockMenus(this, messages, marketService, portfolioService, priceHistoryService);
        getServer().getPluginManager().registerEvents(new StocksMenuListener(stockMenus), this);

        registerCommands();
        recordInitialHistory();
        scheduleMarketTask();
        getLogger().info("MC-Stocks enabled with " + assetRegistry.all().size() + " configured assets.");
    }

    @Override
    public void onDisable() {
        if (marketTask != null) {
            marketTask.cancel();
        }
        if (database != null) {
            try {
                database.close();
            } catch (SQLException ex) {
                getLogger().warning("Could not close SQLite database cleanly: " + ex.getMessage());
            }
        }
    }

    public void reloadPluginConfig() {
        reloadConfig();
        messages.reload();
        assetRegistry.reload();
        marketService.reload(getConfig());
        priceHistoryService.reload(getConfig().getInt("market.price-history-limit-per-asset", 500));
        limitOrderService.reload(getConfig().getBoolean("limit-orders.enabled", true), getConfig().getInt("limit-orders.max-open-per-player", 10), getConfig().getInt("limit-orders.stale-processing-seconds", 300));
        scheduleMarketTask();
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

    private void scheduleMarketTask() {
        if (marketTask != null) {
            marketTask.cancel();
        }
        long tickSeconds = Math.max(1, getConfig().getLong("market.tick-seconds", 60));
        marketTask = getServer().getScheduler().runTaskTimer(this, this::runMarketTick, tickSeconds * 20L, tickSeconds * 20L);
    }

    private void runMarketTick() {
        try {
            marketService.tick();
            if (marketService.isOpen()) {
                priceHistoryService.record(marketService.allStates());
                limitOrderService.processOpenOrders();
            }
        } catch (SQLException ex) {
            getLogger().warning("Market tick failed: " + ex.getMessage());
        }
    }

    private void recordInitialHistory() {
        try {
            priceHistoryService.record(marketService.allStates());
        } catch (SQLException ex) {
            getLogger().warning("Could not record initial price history: " + ex.getMessage());
        }
    }
}
