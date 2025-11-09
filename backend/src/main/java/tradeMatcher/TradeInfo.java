package tradeMatcher;

public final class TradeInfo {
    private final long orderId;
    private final int price;
    private final int quantity;

    public TradeInfo(long orderId, int price, int quantity) {
        this.orderId = orderId;
        this.price = price;
        this.quantity = quantity;
    }

    public long getOrderId() {
        return orderId;
    }

    public int getPrice() {
        return price;
    }

    public int getQuantity() {
        return quantity;
    }
}
