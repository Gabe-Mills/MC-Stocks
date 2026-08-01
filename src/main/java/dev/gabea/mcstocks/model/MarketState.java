package dev.gabea.mcstocks.model;

public final class MarketState {
    private final Asset asset;
    private double price;
    private double openPrice;
    private double previousPrice;

    public MarketState(Asset asset) {
        this.asset = asset;
        this.price = asset.initialPrice();
        this.openPrice = asset.initialPrice();
        this.previousPrice = asset.initialPrice();
    }

    public Asset asset() {
        return asset;
    }

    public double price() {
        return price;
    }

    public double openPrice() {
        return openPrice;
    }

    public double previousPrice() {
        return previousPrice;
    }

    public double changePercent() {
        if (openPrice <= 0.0) {
            return 0.0;
        }
        return ((price - openPrice) / openPrice) * 100.0;
    }

    public void setPrice(double price) {
        this.previousPrice = this.price;
        this.price = Math.max(asset.minPrice(), price);
    }

    public void resetOpenPrice() {
        this.openPrice = price;
    }

    public void restore(double price, double openPrice, double previousPrice) {
        this.price = Math.max(asset.minPrice(), price);
        this.openPrice = Math.max(asset.minPrice(), openPrice);
        this.previousPrice = Math.max(asset.minPrice(), previousPrice);
    }
}
