package tradeMatcher;

import java.util.Objects;

/**
 * Represents a single order submitted to the matching engine, including the core
 * execution properties and market-maker specific directives such as post-only and
 * iceberg display sizing.
 */
public final class Order {

    private final String orderId;
    private final long numericOrderId;
    private final String userId;
    private final String ticker;
    private final OrderSide side;
    private OrderType orderType;
    private TimeInForce timeInForce;
    private final long quantity;
    private double price;
    private double triggerPrice;
    private final boolean postOnly;
    private final long displayQuantity;
    private long remainingQuantity;

    /**
     * Constructs an immutable order instance.
     *
     * @param orderId        unique identifier for the order
     * @param userId         identifier of the submitting user or strategy
     * @param ticker         instrument symbol (e.g., "AAPL")
     * @param side           side of the market the order targets
     * @param orderType      execution logic for the order
     * @param timeInForce    lifetime policy for the order
     * @param quantity       total quantity in whole units
     * @param price          limit price for {@link OrderType#LIMIT} and {@link OrderType#STOP_LIMIT} orders
     * @param triggerPrice   trigger price for stop-style orders
     * @param postOnly       whether the order must only provide liquidity
     * @param displayQuantity visible quantity exposed on the order book for iceberg orders
     */
    public Order(
            String orderId,
            String userId,
            String ticker,
            OrderSide side,
            OrderType orderType,
            TimeInForce timeInForce,
            long quantity,
            double price,
            double triggerPrice,
            boolean postOnly,
            long displayQuantity) {

        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.numericOrderId = parseOrderId(orderId);
        this.userId = Objects.requireNonNull(userId, "userId");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.side = Objects.requireNonNull(side, "side");
        this.orderType = Objects.requireNonNull(orderType, "orderType");
        this.timeInForce = Objects.requireNonNull(timeInForce, "timeInForce");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        this.quantity = quantity;
        this.price = price;
        this.triggerPrice = triggerPrice;
        this.postOnly = postOnly;
        if (displayQuantity < 0) {
            throw new IllegalArgumentException("displayQuantity cannot be negative");
        }
        if (displayQuantity > quantity) {
            throw new IllegalArgumentException("displayQuantity cannot exceed total quantity");
        }
        this.displayQuantity = displayQuantity;
        this.remainingQuantity = quantity;
    }

    private static long parseOrderId(String orderId) {
        try {
            return Long.parseLong(orderId);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("orderId must be numeric for matching engine operations", ex);
        }
    }

    public String getOrderId() {
        return orderId;
    }

    public long getNumericOrderId() {
        return numericOrderId;
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

    public TimeInForce getTimeInForce() {
        return timeInForce;
    }

    public long getQuantity() {
        return quantity;
    }

    public double getPrice() {
        return price;
    }

    public double getTriggerPrice() {
        return triggerPrice;
    }

    public boolean isPostOnly() {
        return postOnly;
    }

    public long getDisplayQuantity() {
        return displayQuantity;
    }

    public long getRemainingQuantity() {
        return remainingQuantity;
    }

    public long getInitialQuantity() {
        return quantity;
    }

    public long getFilledQuantity() {
        return quantity - remainingQuantity;
    }

    public boolean isFilled() {
        return remainingQuantity == 0;
    }

    public void fill(long executedQuantity) {
        if (executedQuantity <= 0) {
            throw new IllegalArgumentException("executedQuantity must be positive");
        }
        if (executedQuantity > remainingQuantity) {
            throw new IllegalArgumentException("executedQuantity exceeds remaining quantity");
        }
        remainingQuantity -= executedQuantity;
    }

    public void Fill(long executedQuantity) {
        fill(executedQuantity);
    }

    public void convertMarketToLimit(double newPrice, TimeInForce lifetime) {
        if (orderType != OrderType.MARKET) {
            throw new IllegalStateException("Only market orders can be converted to limit orders");
        }
        if (newPrice <= 0.0) {
            throw new IllegalArgumentException("Converted price must be positive");
        }
        this.price = newPrice;
        this.orderType = OrderType.LIMIT;
        this.timeInForce = lifetime != null ? lifetime : TimeInForce.GTC;
    }

    // Legacy compatibility helpers ---------------------------------------------------------

    public long GetOrderId() {
        return getNumericOrderId();
    }

    public OrderSide GetSide() {
        return getSide();
    }

    public OrderType GetOrderType() {
        return getOrderType();
    }

    public TimeInForce GetTimeInForce() {
        return getTimeInForce();
    }

    public double GetPrice() {
        return getPrice();
    }

    public long GetInitialQuantity() {
        return getInitialQuantity();
    }

    public long GetRemainingQuantity() {
        return getRemainingQuantity();
    }

    public boolean IsFilled() {
        return isFilled();
    }

    public void ToGoodTillCancel(double newPrice) {
        convertMarketToLimit(newPrice, TimeInForce.GTC);
    }
}
