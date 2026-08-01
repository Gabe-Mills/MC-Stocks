package dev.gabea.mcstocks.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public final class StocksMenuListener implements Listener {
    private final StockMenus stockMenus;

    public StocksMenuListener(StockMenus stockMenus) {
        this.stockMenus = stockMenus;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof StocksMenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        stockMenus.handleClickError(player, () -> holder.run(event.getRawSlot(), player));
    }
}
