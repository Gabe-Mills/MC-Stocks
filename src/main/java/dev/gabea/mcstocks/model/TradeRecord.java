package dev.gabea.mcstocks.model;

import java.util.UUID;

public record TradeRecord(
        long id,
        UUID playerId,
        String symbol,
        String side,
        double quantity,
        double price,
        double gross,
        double fee,
        double tax,
        boolean reversed,
        long createdAt
) {
}
