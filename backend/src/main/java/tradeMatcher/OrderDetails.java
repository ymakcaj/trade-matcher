package tradeMatcher;

/**
 * Immutable view of an order's current state.
 */
public final class OrderDetails {
    private final long orderId;
    private final Side side;
    private final OrderType orderType;
    private final int price;
    private final int remainingQuantity;

    public OrderDetails(long orderId, Side side, OrderType orderType, int price, int remainingQuantity) {
        this.orderId = orderId;
        this.side = side;
        this.orderType = orderType;
        this.price = price;
        this.remainingQuantity = remainingQuantity;
    }

    public long getOrderId() {
        return orderId;
    }

    public Side getSide() {
        return side;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public int getPrice() {
        return price;
    }

    public int getRemainingQuantity() {
        return remainingQuantity;
    }
}
