package dev.gabea.mcstocks.model;

public record MarketRules(double feePercent, double taxPercent, double minTradeValue, double maxTradeValue) {
}
