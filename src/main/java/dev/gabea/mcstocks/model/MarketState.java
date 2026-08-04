package dev.gabea.mcstocks.model;

public final class MarketState {
    private final Asset asset;
    private double price;
    private double openPrice;
    private double previousPrice;
    private boolean frozen;

    public MarketState(Asset asset) {
        this.asset = asset;
        this.price = asset.initialPrice();
        this.openPrice = asset.initialPrice();
        this.previousPrice = asset.initialPrice();
        this.frozen = false;
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

    public boolean frozen() {
        return frozen;
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    public double changePercent() {
        if (openPrice <= 0.0) {
            return 0.0;
        }
        return ((price - openPrice) / openPrice) * 100.0;
    }

    public void setPrice(double price) {
        this.previousPrice = this.price;
        this.price = clamp(price);
    }

    public void resetOpenPrice() {
        this.openPrice = price;
        this.frozen = false;
    }

    public void resetToInitial() {
        this.previousPrice = this.price;
        this.price = asset.initialPrice();
        this.openPrice = asset.initialPrice();
        this.frozen = false;
    }

    public void restore(double price, double openPrice, double previousPrice, boolean frozen) {
        this.price = clamp(price);
        this.openPrice = Math.max(asset.minPrice(), openPrice);
        this.previousPrice = Math.max(asset.minPrice(), previousPrice);
        this.frozen = frozen;
    }

    public void restore(double price, double openPrice, double previousPrice) {
        restore(price, openPrice, previousPrice, false);
    }

    private double clamp(double value) {
        double clamped = Math.max(asset.minPrice(), value);
        if (asset.maxPrice() > 0.0) {
            clamped = Math.min(asset.maxPrice(), clamped);
        }
        return clamped;
    }
}
