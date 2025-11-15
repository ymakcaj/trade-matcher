package tradeMatcher;

public final class TradeInfo {
    private final long orderId;
    private final double price;
    private final int quantity;

    public TradeInfo(long orderId, double price, int quantity) {
        this.orderId = orderId;
        this.price = price;
        this.quantity = quantity;
    }

    public long getOrderId() {
        return orderId;
    }

    public double getPrice() {
        return price;
    }

    public int getQuantity() {
        return quantity;
    }
}
