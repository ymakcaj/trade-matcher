package tradeMatcher;

public final class OrderModify {
    private final long orderId;
    private final int price;
    private final OrderSide side;
    private final int quantity;
    private final String userId;
    private final String ticker;

    public OrderModify(long orderId, String userId, String ticker, OrderSide side, int price, int quantity) {
        this.orderId = orderId;
        this.price = price;
        this.side = side;
        this.quantity = quantity;
        this.userId = userId;
        this.ticker = ticker;
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

    public String getUserId() {
        return userId;
    }

    public String getTicker() {
        return ticker;
    }

    public Order ToOrderPointer(OrderType type, TimeInForce timeInForce) {
        return new Order(
                String.valueOf(GetOrderId()),
                userId,
                ticker,
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
