package dev.gabea.mcstocks.model;

import java.util.UUID;

public record LimitOrder(
        long id,
        UUID playerId,
        String symbol,
        OrderSide side,
        double quantity,
        double targetPrice,
        String status,
        long createdAt,
        Long executedAt
) {
}
