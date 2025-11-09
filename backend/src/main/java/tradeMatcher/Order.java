package tradeMatcher;

public final class Order {
    private OrderType orderType;
    private final long orderId;
    private final Side side;
    private int price;
    private final int initialQuantity;
    private int remainingQuantity;

    public Order(OrderType orderType, long orderId, Side side, int price, int quantity) {
        this.orderType = orderType;
        this.orderId = orderId;
        this.side = side;
        this.price = price;
        this.initialQuantity = quantity;
        this.remainingQuantity = quantity;
    }

    public Order(long orderId, Side side, int quantity) {
        this(OrderType.Market, orderId, side, Constants.INVALID_PRICE, quantity);
    }

    public long GetOrderId() {
        return orderId;
    }

    public Side GetSide() {
        return side;
    }

    public int GetPrice() {
        return price;
    }

    public OrderType GetOrderType() {
        return orderType;
    }

    public int GetInitialQuantity() {
        return initialQuantity;
    }

    public int GetRemainingQuantity() {
        return remainingQuantity;
    }

    public int GetFilledQuantity() {
        return GetInitialQuantity() - GetRemainingQuantity();
    }

    public boolean IsFilled() {
        return GetRemainingQuantity() == 0;
    }

    public void Fill(int quantity) {
        if (quantity > GetRemainingQuantity()) {
            throw new IllegalStateException("Order cannot be filled for more than its remaining quantity.");
        }

        remainingQuantity -= quantity;
    }

    public void ToGoodTillCancel(int price) {
        if (GetOrderType() != OrderType.Market) {
            throw new IllegalStateException("Only market orders can have their price adjusted.");
        }

        this.price = price;
        this.orderType = OrderType.GoodTillCancel;
    }
}
