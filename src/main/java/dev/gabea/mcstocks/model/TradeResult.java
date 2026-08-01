package dev.gabea.mcstocks.model;

public record TradeResult(
        boolean success,
        String messageKey,
        String symbol,
        double quantity,
        double gross,
        double fee,
        double total
) {
    public static TradeResult failure(String messageKey, String symbol) {
        return new TradeResult(false, messageKey, symbol, 0.0, 0.0, 0.0, 0.0);
    }

    public static TradeResult failure(String messageKey, String symbol, double quantity, double gross, double fee, double total) {
        return new TradeResult(false, messageKey, symbol, quantity, gross, fee, total);
    }

    public static TradeResult success(String messageKey, String symbol, double quantity, double gross, double fee, double total) {
        return new TradeResult(true, messageKey, symbol, quantity, gross, fee, total);
    }
}
