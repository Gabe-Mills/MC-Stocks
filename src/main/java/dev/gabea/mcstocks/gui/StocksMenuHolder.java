package dev.gabea.mcstocks.gui;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class StocksMenuHolder implements InventoryHolder {
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();
    private Inventory inventory;

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public void action(int slot, Consumer<Player> action) {
        actions.put(slot, action);
    }

    public void run(int slot, Player player) {
        Consumer<Player> action = actions.get(slot);
        if (action != null) {
            action.accept(player);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
