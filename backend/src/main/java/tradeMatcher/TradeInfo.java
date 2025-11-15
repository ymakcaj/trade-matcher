package tradeMatcher;

public final class TradeInfo {
    private final long orderId;
    private final String userId;
    private final String ticker;
    private final OrderSide side;
    private final double price;
    private final int quantity;

    public TradeInfo(long orderId, String userId, String ticker, OrderSide side, double price, int quantity) {
        this.orderId = orderId;
        this.userId = userId;
        this.ticker = ticker;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
    }

    public long getOrderId() {
        return orderId;
    }

    public String getUserId() {
        return userId;
    }

    public String getTicker() {
        return ticker;
    }

    public OrderSide getSide() {
        return side;
    }

    public double getPrice() {
        return price;
    }

    public int getQuantity() {
        return quantity;
    }
}
