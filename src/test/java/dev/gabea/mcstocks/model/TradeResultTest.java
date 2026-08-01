package dev.gabea.mcstocks.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeResultTest {
    @Test
    void failureWithDetailsIsStillFailure() {
        TradeResult result = TradeResult.failure("insufficient-funds", "IRON", 2.0, 100.0, 1.0, 101.0);

        assertFalse(result.success());
    }

    @Test
    void successResultIsSuccess() {
        TradeResult result = TradeResult.success("buy-success", "IRON", 2.0, 100.0, 1.0, 101.0);

        assertTrue(result.success());
    }
}
