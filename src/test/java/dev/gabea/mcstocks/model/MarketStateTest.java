package dev.gabea.mcstocks.model;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketStateTest {
    private static Asset iron() {
        return new Asset("IRON", "IronWorks", AssetType.STOCK, 100.0, 5.0, 500.0, 0.02, 0.0, false, Material.IRON_INGOT, true);
    }

    @Test
    void setPriceClampsToAssetMinimum() {
        MarketState state = new MarketState(iron());

        state.setPrice(1.0);

        assertEquals(5.0, state.price());
    }

    @Test
    void setPriceClampsToAssetMaximum() {
        MarketState state = new MarketState(iron());

        state.setPrice(9999.0);

        assertEquals(500.0, state.price());
    }

    @Test
    void changePercentUsesOpenPrice() {
        MarketState state = new MarketState(iron());

        state.setPrice(125.0);

        assertEquals(25.0, state.changePercent());
    }

    @Test
    void restorePreservesMarketDayValues() {
        MarketState state = new MarketState(iron());

        state.restore(120.0, 80.0, 110.0, true);

        assertEquals(120.0, state.price());
        assertEquals(80.0, state.openPrice());
        assertEquals(110.0, state.previousPrice());
        assertEquals(50.0, state.changePercent());
        assertTrue(state.frozen());
    }
}
