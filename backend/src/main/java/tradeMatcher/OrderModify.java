package tradeMatcher;

public final class OrderModify {
    private final long orderId;
    private final int price;
    private final OrderSide side;
    private final int quantity;

    public OrderModify(long orderId, OrderSide side, int price, int quantity) {
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

    public OrderSide GetSide() {
        return side;
    }

    public int GetQuantity() {
        return quantity;
    }

    public Order ToOrderPointer(OrderType type, TimeInForce timeInForce) {
        return new Order(
                String.valueOf(GetOrderId()),
                "modify",
                "UNKNOWN",
                GetSide(),
                type,
                timeInForce,
                GetQuantity(),
                GetPrice(),
                0.0,
                false,
                GetQuantity());
    }
}
