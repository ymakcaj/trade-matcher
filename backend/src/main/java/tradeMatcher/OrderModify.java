package tradeMatcher;

public final class OrderModify {
    private final long orderId;
    private final int price;
    private final Side side;
    private final int quantity;

    public OrderModify(long orderId, Side side, int price, int quantity) {
        this.orderId = orderId;
        this.price = price;
        this.side = side;
        this.quantity = quantity;
    }

    public long GetOrderId() {
        return orderId;
    }

    public int GetPrice() {
        return price;
    }

    public Side GetSide() {
        return side;
    }

    public int GetQuantity() {
        return quantity;
    }

    public Order ToOrderPointer(OrderType type) {
        return new Order(type, GetOrderId(), GetSide(), GetPrice(), GetQuantity());
    }
}
