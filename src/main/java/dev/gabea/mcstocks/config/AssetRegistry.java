package dev.gabea.mcstocks.config;

import dev.gabea.mcstocks.model.Asset;
import dev.gabea.mcstocks.model.AssetType;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class AssetRegistry {
    private final JavaPlugin plugin;
    private final Map<String, Asset> assets = new LinkedHashMap<>();

    public AssetRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "assets.yml");
        if (!file.exists()) {
            plugin.saveResource("assets.yml", false);
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection("assets");
        assets.clear();
        if (section == null) {
            return;
        }

        for (String key : section.getKeys(false)) {
            ConfigurationSection assetSection = section.getConfigurationSection(key);
            if (assetSection == null) {
                continue;
            }

            String symbol = key.toUpperCase(Locale.ROOT);
            Material material = Material.matchMaterial(assetSection.getString("display-material", "PAPER"));
            if (material == null) {
                material = Material.PAPER;
            }

            AssetType type;
            try {
                type = AssetType.valueOf(assetSection.getString("type", "STOCK").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                type = AssetType.STOCK;
            }

            Asset asset = new Asset(
                    symbol,
                    assetSection.getString("name", symbol),
                    type,
                    Math.max(0.01, assetSection.getDouble("initial-price", 10.0)),
                    Math.max(0.01, assetSection.getDouble("min-price", 0.01)),
                    Math.max(0.0, assetSection.getDouble("volatility", 0.02)),
                    assetSection.getDouble("trend", 0.0),
                    assetSection.getBoolean("decimal-trading", type == AssetType.CRYPTO),
                    material,
                    assetSection.getBoolean("enabled", true)
            );
            assets.put(symbol, asset);
        }
    }

    public Optional<Asset> find(String symbol) {
        if (symbol == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(assets.get(symbol.toUpperCase(Locale.ROOT)));
    }

    public Collection<Asset> all() {
        return assets.values();
    }
}
