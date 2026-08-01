package dev.gabea.mcstocks.model;

public record Holding(String symbol, double quantity, double averageCost) {
    public double marketValue(double price) {
        return quantity * price;
    }
}
