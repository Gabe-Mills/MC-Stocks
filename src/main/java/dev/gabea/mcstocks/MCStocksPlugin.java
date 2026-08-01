package dev.gabea.mcstocks;

import dev.gabea.mcstocks.command.StocksAdminCommand;
import dev.gabea.mcstocks.command.StocksCommand;
import dev.gabea.mcstocks.config.AssetRegistry;
import dev.gabea.mcstocks.config.MessageService;
import dev.gabea.mcstocks.gui.StockMenus;
import dev.gabea.mcstocks.gui.StocksMenuListener;
import dev.gabea.mcstocks.service.EconomyService;
import dev.gabea.mcstocks.service.MarketService;
import dev.gabea.mcstocks.service.PortfolioService;
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
        portfolioService = new PortfolioService(database, marketService, economyService);
        stockMenus = new StockMenus(this, messages, marketService, portfolioService);
        getServer().getPluginManager().registerEvents(new StocksMenuListener(stockMenus), this);

        registerCommands();
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

    public StockMenus stockMenus() {
        return stockMenus;
    }

    private void registerCommands() {
        StocksCommand stocksCommand = new StocksCommand(this, messages, marketService, portfolioService, stockMenus);
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
        marketTask = getServer().getScheduler().runTaskTimer(this, marketService::tick, tickSeconds * 20L, tickSeconds * 20L);
    }
}
