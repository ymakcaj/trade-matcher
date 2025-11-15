package tradeMatcher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MatchingEngine {

    private Orderbook orderbook = new Orderbook();
    private final AccountManager accountManager;
    private Consumer<OrderbookLevelInfos> orderBookUpdateListener;
    private Consumer<List<Trade>> tradeListener;
    private final List<Consumer<FillRecord>> fillListeners = new CopyOnWriteArrayList<>();
    private final Map<String, List<FillRecord>> fillsByUser = new ConcurrentHashMap<>();
    private final AtomicLong fillSequence = new AtomicLong(1L);
    private static final Logger LOG = LoggerFactory.getLogger(MatchingEngine.class);

    public MatchingEngine(AccountManager accountManager) {
        this.accountManager = accountManager;
    }

    public void onOrderBookUpdate(Consumer<OrderbookLevelInfos> listener) {
        this.orderBookUpdateListener = listener;
    }

    public void onTrades(Consumer<List<Trade>> listener) {
        this.tradeListener = listener;
    }

    public void onFill(Consumer<FillRecord> listener) {
        if (listener != null) {
            this.fillListeners.add(listener);
        }
    }

    public void processOrder(Order order) {
        Objects.requireNonNull(order, "order");
        PriceScale scale = PriceScaleProvider.getRegistry().getScale(order.getTicker());
        double displayPrice = scale.toDisplayPrice((int) Math.round(order.GetPrice()));

        UserAccount account = accountManager.findById(order.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("UNKNOWN_USER"));

        enforcePostOnly(order);

        if (order.GetSide() == OrderSide.BUY && order.GetOrderType() != OrderType.MARKET) {
            accountManager.ensureSufficientBuyingPower(account, order.getTicker(), displayPrice, order.GetInitialQuantity());
        }
        if (order.GetSide() == OrderSide.SELL) {
            accountManager.ensureSufficientInventory(account, order.getTicker(), order.GetInitialQuantity());
        }

        LOG.info("Processing order: user={}, type={}, side={}, price={}, qty={}, id={}",
                order.getUserId(),
                order.GetOrderType(),
                order.GetSide(),
                displayPrice,
                order.GetInitialQuantity(),
                order.GetOrderId());

        List<Trade> trades = orderbook.AddOrder(order);
        handleTrades(trades);
        broadcastOrderBook();
        broadcastTrades(trades);
    }

    public void modifyOrder(String userId, long orderId, OrderSide side, int price, int quantity) {
        LOG.info("Modifying order: user={}, id={}, side={}, price={}, qty={}", userId, orderId, side, price, quantity);
        Order existing = orderbook.findOrder(orderId);
        if (existing == null || !existing.getUserId().equals(userId)) {
            throw new IllegalArgumentException("ORDER_NOT_FOUND");
        }
        OrderModify modify = new OrderModify(orderId, userId, existing.getTicker(), side, price, quantity);
        List<Trade> trades = orderbook.ModifyOrder(modify);
        handleTrades(trades);
        broadcastOrderBook();
        broadcastTrades(trades);
    }

    public boolean cancelOrder(String userId, long orderId) {
        LOG.info("Canceling order: user={}, id={}", userId, orderId);
        Order existing = orderbook.findOrder(orderId);
        if (existing == null || !existing.getUserId().equals(userId)) {
            return false;
        }
        orderbook.CancelOrder(orderId);
        broadcastOrderBook();
        return true;
    }

    public OrderbookLevelInfos getOrderbookLevels() {
        return orderbook.GetOrderInfos();
    }

    public List<OrderDetails> getOpenOrdersForUser(String userId) {
        List<OrderDetails> details = orderbook.GetOrderDetails();
        if (details.isEmpty()) {
            return List.of();
        }
        List<OrderDetails> filtered = new ArrayList<>();
        for (OrderDetails detail : details) {
            if (userId.equals(detail.getUserId())) {
                filtered.add(detail);
            }
        }
        return filtered;
    }

    public List<FillRecord> getFillsForUser(String userId) {
        List<FillRecord> fills = fillsByUser.get(userId);
        if (fills == null) {
            return List.of();
        }
        return Collections.unmodifiableList(fills);
    }

    private void broadcastOrderBook() {
        if (orderBookUpdateListener != null) {
            orderBookUpdateListener.accept(orderbook.GetOrderInfos());
        }
    }

    private void broadcastTrades(List<Trade> trades) {
        if (tradeListener != null && trades != null && !trades.isEmpty()) {
            tradeListener.accept(trades);
        }
    }

    public synchronized void reset() {
        LOG.info("Resetting matching engine");
        if (orderbook != null) {
            orderbook.close();
        }
        orderbook = new Orderbook();
        fillsByUser.clear();
        broadcastOrderBook();
    }

    private void enforcePostOnly(Order order) {
        if (!order.isPostOnly()) {
            return;
        }
        Integer bestOpposite = order.GetSide() == OrderSide.BUY
                ? orderbook.getBestAskPriceKey()
                : orderbook.getBestBidPriceKey();
        if (bestOpposite == null) {
            return;
        }
        int priceKey = (int) Math.round(order.GetPrice());
        if (order.GetSide() == OrderSide.BUY && priceKey >= bestOpposite) {
            throw new IllegalArgumentException("POST_ONLY_WOULD_TRADE");
        }
        if (order.GetSide() == OrderSide.SELL && priceKey <= bestOpposite) {
            throw new IllegalArgumentException("POST_ONLY_WOULD_TRADE");
        }
    }

    private void handleTrades(List<Trade> trades) {
        if (trades == null || trades.isEmpty()) {
            return;
        }
        for (Trade trade : trades) {
            recordFill(trade.getBidTrade());
            recordFill(trade.getAskTrade());
        }
    }

    private void recordFill(TradeInfo info) {
        if (info == null) {
            return;
        }

        accountManager.findById(info.getUserId()).ifPresent(account -> {
            double notional = info.getPrice() * info.getQuantity();
            if (info.getSide() == OrderSide.BUY) {
                account.adjustCash(-notional);
                account.adjustPosition(info.getTicker(), info.getQuantity());
            } else {
                account.adjustCash(notional);
                account.adjustPosition(info.getTicker(), -info.getQuantity());
            }
        });

        FillRecord fill = new FillRecord(
                nextFillId(),
                Long.toString(info.getOrderId()),
                info.getUserId(),
                info.getTicker(),
                info.getSide(),
                info.getPrice(),
                info.getQuantity(),
                Instant.now());

        fillsByUser.computeIfAbsent(info.getUserId(), __ -> new CopyOnWriteArrayList<>()).add(fill);
        for (Consumer<FillRecord> listener : fillListeners) {
            try {
                listener.accept(fill);
            } catch (Exception ex) {
                LOG.warn("Fill listener failed", ex);
            }
        }
    }

    private String nextFillId() {
        return Long.toString(fillSequence.getAndIncrement());
    }
}
