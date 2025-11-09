package tradeMatcher;

import java.util.List;

/**
 * An API for interacting with the order book.
 * This class is intended to be used by a front-end or another service.
 */
public class OrderbookApi {

    private final Orderbook orderbook;

    public OrderbookApi() {
        this.orderbook = new Orderbook();
    }

    /**
     * Adds a new order to the order book.
     *
     * @param order The order to add.
     * @return A list of trades that occurred as a result of adding the order.
     */
    public List<Trade> addOrder(Order order) {
        return orderbook.AddOrder(order);
    }

    /**
     * Cancels an existing order.
     *
     * @param orderId The ID of the order to cancel.
     */
    public void cancelOrder(long orderId) {
        orderbook.CancelOrder(orderId);
    }

    /**
     * Modifies an existing order.
     *
     * @param orderModify The modification details.
     * @return A list of trades that occurred as a result of the modification.
     */
    public List<Trade> modifyOrder(OrderModify orderModify) {
        return orderbook.ModifyOrder(orderModify);
    }

    /**
     * Gets the current state of the order book.
     *
     * @return An object containing the current bids, asks, and open orders.
     */
    public OrderbookSnapshot getOrderbookSnapshot() {
        List<LevelInfo> bids = orderbook.GetOrderInfos().GetBids();
        List<LevelInfo> asks = orderbook.GetOrderInfos().GetAsks();
        List<OrderDetails> orders = orderbook.GetOrderDetails();
        return new OrderbookSnapshot(bids, asks, orders);
    }

    /**
     * A snapshot of the order book at a point in time.
     */
    public record OrderbookSnapshot(List<LevelInfo> bids, List<LevelInfo> asks, List<OrderDetails> openOrders) {
    }
}
