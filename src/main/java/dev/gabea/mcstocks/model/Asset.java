package dev.gabea.mcstocks.model;

import org.bukkit.Material;

public record Asset(
        String symbol,
        String name,
        AssetType type,
        double initialPrice,
        double minPrice,
        double maxPrice,
        double volatility,
        double trend,
        boolean decimalTrading,
        Material displayMaterial,
        boolean enabled
) {
}
