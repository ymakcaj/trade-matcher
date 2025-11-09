package tradeMatcher;

import com.google.gson.Gson;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MatchingEngine {

    private Orderbook orderbook = new Orderbook();
    private final Gson gson = new Gson();
    private Consumer<String> orderBookUpdateListener;
    private Consumer<String> tradeListener;
    private static final Logger LOG = LoggerFactory.getLogger(MatchingEngine.class);

    public void onOrderBookUpdate(Consumer<String> listener) {
        this.orderBookUpdateListener = listener;
    }

    public void onTrades(Consumer<String> listener) {
        this.tradeListener = listener;
    }

    public void processOrder(Order order) {
        // This is a simplified example. You would have more complex logic here
        // to handle different order types (add, cancel, modify).
        LOG.info("Processing order: type={}, side={}, price={}, qty={}, id={}",
                order.GetOrderType(), order.GetSide(), order.GetPrice(),
                order.GetInitialQuantity(), order.GetOrderId());
        List<Trade> trades = orderbook.AddOrder(order);
        broadcastOrderBook();
        broadcastTrades(trades, "process");
    }

    public void modifyOrder(long orderId, Side side, int price, int quantity) {
        LOG.info("Modifying order: id={}, side={}, price={}, qty={}", orderId, side, price, quantity);
        OrderModify modify = new OrderModify(orderId, side, price, quantity);
        List<Trade> trades = orderbook.ModifyOrder(modify);
        broadcastOrderBook();
        broadcastTrades(trades, "modify");
    }

    public void cancelOrder(long orderId) {
        LOG.info("Canceling order: id={}", orderId);
        orderbook.CancelOrder(orderId);
        broadcastOrderBook();
    }

    public String getCurrentOrderBookAsJson() {
        OrderbookLevelInfos infos = orderbook.GetOrderInfos();
        return gson.toJson(infos);
    }

    private void broadcastOrderBook() {
        if (orderBookUpdateListener != null) {
            String orderBookJson = getCurrentOrderBookAsJson();
            LOG.debug("Order book snapshot: {}", orderBookJson);
            orderBookUpdateListener.accept(orderBookJson);
        }
    }

    private void broadcastTrades(List<Trade> trades, String source) {
        if (tradeListener != null && trades != null && !trades.isEmpty()) {
            String tradesJson = gson.toJson(trades);
            LOG.debug("Broadcasting {} trades from {} action", trades.size(), source);
            tradeListener.accept(tradesJson);
        }
    }

    public synchronized void reset() {
        LOG.info("Resetting matching engine");
        if (orderbook != null) {
            orderbook.close();
        }
        orderbook = new Orderbook();
        broadcastOrderBook();
        if (tradeListener != null) {
            tradeListener.accept("[]");
        }
    }
}
