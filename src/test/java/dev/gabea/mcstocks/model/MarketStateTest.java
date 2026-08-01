package dev.gabea.mcstocks.model;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketStateTest {
    @Test
    void setPriceClampsToAssetMinimum() {
        Asset asset = new Asset("IRON", "IronWorks", AssetType.STOCK, 100.0, 5.0, 0.02, 0.0, false, Material.IRON_INGOT, true);
        MarketState state = new MarketState(asset);

        state.setPrice(1.0);

        assertEquals(5.0, state.price());
    }

    @Test
    void changePercentUsesOpenPrice() {
        Asset asset = new Asset("IRON", "IronWorks", AssetType.STOCK, 100.0, 5.0, 0.02, 0.0, false, Material.IRON_INGOT, true);
        MarketState state = new MarketState(asset);

        state.setPrice(125.0);

        assertEquals(25.0, state.changePercent());
    }

    @Test
    void restorePreservesMarketDayValues() {
        Asset asset = new Asset("IRON", "IronWorks", AssetType.STOCK, 100.0, 5.0, 0.02, 0.0, false, Material.IRON_INGOT, true);
        MarketState state = new MarketState(asset);

        state.restore(120.0, 80.0, 110.0);

        assertEquals(120.0, state.price());
        assertEquals(80.0, state.openPrice());
        assertEquals(110.0, state.previousPrice());
        assertEquals(50.0, state.changePercent());
    }
}
