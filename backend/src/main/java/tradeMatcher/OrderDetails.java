package tradeMatcher;

/**
 * Immutable view of an order's current state.
 */
public final class OrderDetails {
    private final long orderId;
    private final String userId;
    private final String ticker;
    private final OrderSide side;
    private final OrderType orderType;
    private final double price;
    private final long remainingQuantity;

    public OrderDetails(long orderId, String userId, String ticker, OrderSide side, OrderType orderType, double price, long remainingQuantity) {
        this.orderId = orderId;
        this.userId = userId;
        this.ticker = ticker;
        this.side = side;
        this.orderType = orderType;
        this.price = price;
        this.remainingQuantity = remainingQuantity;
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

    public OrderType getOrderType() {
        return orderType;
    }

    public double getPrice() {
        return price;
    }

    public long getRemainingQuantity() {
        return remainingQuantity;
    }
}
