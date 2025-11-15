package tradeMatcher;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class Orderbook implements AutoCloseable {
    private static final class OrderEntry {
        final Order order;

        OrderEntry(Order order) {
            this.order = order;
        }
    }

    private static final class LevelData {
        int quantity;
        int count;

        enum Action {
            Add,
            Remove,
            Match
        }
    }

    private final Map<Integer, LevelData> data = new HashMap<>();
    private final NavigableMap<Integer, Deque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Integer, Deque<Order>> asks = new TreeMap<>();
    private final Map<Long, OrderEntry> orders = new HashMap<>();
    private final ReentrantLock ordersLock = new ReentrantLock();
    private final Condition shutdownCondition = ordersLock.newCondition();
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final Thread ordersPruneThread;

    public Orderbook() {
        ordersPruneThread = new Thread(this::PruneGoodForDayOrders, "orderbook-prune");
        ordersPruneThread.start();
    }

    private static int priceKey(Order order) {
        return (int) Math.round(order.GetPrice());
    }

    private static double displayPrice(int price, Order order) {
        PriceScale scale = PriceScaleProvider.getRegistry().getScale(order.getTicker());
        return scale.toDisplayPrice(price);
    }

    private void PruneGoodForDayOrders() {
        final ZoneId zone = ZoneId.systemDefault();

        while (true) {
            Instant nowInstant = Instant.now();
            ZonedDateTime now = ZonedDateTime.ofInstant(nowInstant, zone);
            ZonedDateTime next = now.withHour(16).withMinute(0).withSecond(0).withNano(0);
            if (!now.isBefore(next)) {
                next = next.plusDays(1);
            }
            Duration delay = Duration.between(nowInstant, next).plusMillis(100);
            long nanos = Math.max(0L, delay.toNanos());

            ordersLock.lock();
            try {
                if (shutdown.get()) {
                    return;
                }

                while (!shutdown.get() && nanos > 0L) {
                    nanos = shutdownCondition.awaitNanos(nanos);
                }

                if (shutdown.get()) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                ordersLock.unlock();
            }

            List<Long> orderIds = new ArrayList<>();

            ordersLock.lock();
            try {
                for (OrderEntry entry : orders.values()) {
                    if (entry.order.GetTimeInForce() == TimeInForce.DAY) {
                        orderIds.add(entry.order.GetOrderId());
                    }
                }
            } finally {
                ordersLock.unlock();
            }

            if (!orderIds.isEmpty()) {
                CancelOrders(orderIds);
            }
        }
    }

    private void CancelOrders(List<Long> orderIds) {
        ordersLock.lock();
        try {
            for (long orderId : orderIds) {
                CancelOrderInternal(orderId);
            }
        } finally {
            ordersLock.unlock();
        }
    }

    private void CancelOrderInternal(long orderId) {
        OrderEntry entry = orders.remove(orderId);
        if (entry == null) {
            return;
        }

        Order order = entry.order;
        NavigableMap<Integer, Deque<Order>> book = order.GetSide() == OrderSide.SELL ? asks : bids;
        Deque<Order> ordersAtPrice = book.get(priceKey(order));
        if (ordersAtPrice != null) {
            ordersAtPrice.remove(order);
            if (ordersAtPrice.isEmpty()) {
                book.remove(priceKey(order));
            }
        }

        OnOrderCancelled(order);
    }

    private void OnOrderCancelled(Order order) {
        UpdateLevelData(priceKey(order), (int) order.GetRemainingQuantity(), LevelData.Action.Remove);
    }

    private void OnOrderAdded(Order order) {
        UpdateLevelData(priceKey(order), (int) order.GetInitialQuantity(), LevelData.Action.Add);
    }

    private void OnOrderMatched(int price, int quantity, boolean isFullyFilled) {
        UpdateLevelData(price, quantity, isFullyFilled ? LevelData.Action.Remove : LevelData.Action.Match);
    }

    private void UpdateLevelData(int price, int quantity, LevelData.Action action) {
        LevelData levelData = data.computeIfAbsent(price, p -> new LevelData());

        if (action == LevelData.Action.Remove) {
            levelData.count -= 1;
        } else if (action == LevelData.Action.Add) {
            levelData.count += 1;
        }

        if (action == LevelData.Action.Remove || action == LevelData.Action.Match) {
            levelData.quantity -= quantity;
        } else {
            levelData.quantity += quantity;
        }

        if (levelData.quantity < 0) {
            levelData.quantity = 0;
        }

        if (levelData.count <= 0) {
            data.remove(price);
        }
    }

    private boolean CanFullyFill(OrderSide side, int price, int quantity) {
        if (!CanMatch(side, price)) {
            return false;
        }

        Integer threshold = null;

        if (side == OrderSide.BUY) {
            Map.Entry<Integer, Deque<Order>> entry = asks.firstEntry();
            if (entry != null) {
                threshold = entry.getKey();
            }
        } else {
            Map.Entry<Integer, Deque<Order>> entry = bids.firstEntry();
            if (entry != null) {
                threshold = entry.getKey();
            }
        }

        for (Map.Entry<Integer, LevelData> dataEntry : data.entrySet()) {
            int levelPrice = dataEntry.getKey();
            LevelData level = dataEntry.getValue();

            if (threshold != null) {
                if (side == OrderSide.BUY && threshold > levelPrice) {
                    continue;
                }
                if (side == OrderSide.SELL && threshold < levelPrice) {
                    continue;
                }
            }

            if (side == OrderSide.BUY && levelPrice > price) {
                continue;
            }
            if (side == OrderSide.SELL && levelPrice < price) {
                continue;
            }

            if (quantity <= level.quantity) {
                return true;
            }

            quantity -= level.quantity;
        }

        return false;
    }

    private boolean CanMatch(OrderSide side, int price) {
        if (side == OrderSide.BUY) {
            Map.Entry<Integer, Deque<Order>> bestAsk = asks.firstEntry();
            if (bestAsk == null) {
                return false;
            }
            return price >= bestAsk.getKey();
        }

        Map.Entry<Integer, Deque<Order>> bestBid = bids.firstEntry();
        if (bestBid == null) {
            return false;
        }
        return price <= bestBid.getKey();
    }

    private List<Trade> MatchOrders() {
        List<Trade> trades = new ArrayList<>(orders.size());

        while (true) {
            if (bids.isEmpty() || asks.isEmpty()) {
                break;
            }

            Map.Entry<Integer, Deque<Order>> bidEntry = bids.firstEntry();
            Map.Entry<Integer, Deque<Order>> askEntry = asks.firstEntry();
            int bidPrice = bidEntry.getKey();
            int askPrice = askEntry.getKey();

            if (bidPrice < askPrice) {
                break;
            }

            Deque<Order> bidOrders = bidEntry.getValue();
            Deque<Order> askOrders = askEntry.getValue();

            while (!bidOrders.isEmpty() && !askOrders.isEmpty()) {
                Order bid = bidOrders.peekFirst();
                Order ask = askOrders.peekFirst();

                int quantity = (int) Math.min(bid.GetRemainingQuantity(), ask.GetRemainingQuantity());

                bid.Fill(quantity);
                ask.Fill(quantity);

                if (bid.IsFilled()) {
                    bidOrders.removeFirst();
                    orders.remove(bid.GetOrderId());
                }

                if (ask.IsFilled()) {
                    askOrders.removeFirst();
                    orders.remove(ask.GetOrderId());
                }

                trades.add(new Trade(
                    new TradeInfo(
                        bid.GetOrderId(),
                        bid.getUserId(),
                        bid.getTicker(),
                        OrderSide.BUY,
                        displayPrice(priceKey(bid), bid),
                        quantity),
                    new TradeInfo(
                        ask.GetOrderId(),
                        ask.getUserId(),
                        ask.getTicker(),
                        OrderSide.SELL,
                        displayPrice(priceKey(ask), ask),
                        quantity)));

                OnOrderMatched(priceKey(bid), quantity, bid.IsFilled());
                OnOrderMatched(priceKey(ask), quantity, ask.IsFilled());
            }

            if (bidOrders.isEmpty()) {
                bids.pollFirstEntry();
                data.remove(bidPrice);
            }

            if (askOrders.isEmpty()) {
                asks.pollFirstEntry();
                data.remove(askPrice);
            }
        }

        if (!bids.isEmpty()) {
            Deque<Order> ordersAtPrice = bids.firstEntry().getValue();
            Order order = ordersAtPrice.peekFirst();
            if (order != null && order.GetTimeInForce() == TimeInForce.IOC) {
                CancelOrderInternal(order.GetOrderId());
            }
        }

        if (!asks.isEmpty()) {
            Deque<Order> ordersAtPrice = asks.firstEntry().getValue();
            Order order = ordersAtPrice.peekFirst();
            if (order != null && order.GetTimeInForce() == TimeInForce.IOC) {
                CancelOrderInternal(order.GetOrderId());
            }
        }

        return trades;
    }

    public List<Trade> AddOrder(Order order) {
        ordersLock.lock();
        try {
            if (orders.containsKey(order.GetOrderId())) {
                return List.of();
            }

            if (order.GetOrderType() == OrderType.MARKET) {
                if (order.GetSide() == OrderSide.BUY && !asks.isEmpty()) {
                    int worstAsk = asks.lastEntry().getKey();
                    order.ToGoodTillCancel(worstAsk);
                } else if (order.GetSide() == OrderSide.SELL && !bids.isEmpty()) {
                    int worstBid = bids.lastEntry().getKey();
                    order.ToGoodTillCancel(worstBid);
                } else {
                    return List.of();
                }
            }

            if (order.GetTimeInForce() == TimeInForce.IOC && !CanMatch(order.GetSide(), (int) Math.round(order.GetPrice()))) {
                return List.of();
            }

            if (order.GetTimeInForce() == TimeInForce.FOK && !CanFullyFill(order.GetSide(), (int) Math.round(order.GetPrice()), (int) order.GetInitialQuantity())) {
                return List.of();
            }

            Deque<Order> priceOrders;
            int priceKey = (int) Math.round(order.GetPrice());
            if (order.GetSide() == OrderSide.BUY) {
                priceOrders = bids.computeIfAbsent(priceKey, __ -> new ArrayDeque<>());
            } else {
                priceOrders = asks.computeIfAbsent(priceKey, __ -> new ArrayDeque<>());
            }

            priceOrders.addLast(order);
            orders.put(order.GetOrderId(), new OrderEntry(order));

            OnOrderAdded(order);

            return MatchOrders();
        } finally {
            ordersLock.unlock();
        }
    }

    public void CancelOrder(long orderId) {
        ordersLock.lock();
        try {
            CancelOrderInternal(orderId);
        } finally {
            ordersLock.unlock();
        }
    }

    public List<Trade> ModifyOrder(OrderModify order) {
        OrderType orderType;
        TimeInForce timeInForce;

        ordersLock.lock();
        try {
            OrderEntry entry = orders.get(order.GetOrderId());
            if (entry == null) {
                return List.of();
            }

            orderType = entry.order.GetOrderType();
            timeInForce = entry.order.GetTimeInForce();
        } finally {
            ordersLock.unlock();
        }

        CancelOrder(order.GetOrderId());
        return AddOrder(order.ToOrderPointer(orderType, timeInForce));
    }

    public int Size() {
        ordersLock.lock();
        try {
            return orders.size();
        } finally {
            ordersLock.unlock();
        }
    }

    public Order findOrder(long orderId) {
        ordersLock.lock();
        try {
            OrderEntry entry = orders.get(orderId);
            return entry != null ? entry.order : null;
        } finally {
            ordersLock.unlock();
        }
    }

    public Integer getBestBidPriceKey() {
        ordersLock.lock();
        try {
            Map.Entry<Integer, Deque<Order>> entry = bids.firstEntry();
            return entry != null ? entry.getKey() : null;
        } finally {
            ordersLock.unlock();
        }
    }

    public Integer getBestAskPriceKey() {
        ordersLock.lock();
        try {
            Map.Entry<Integer, Deque<Order>> entry = asks.firstEntry();
            return entry != null ? entry.getKey() : null;
        } finally {
            ordersLock.unlock();
        }
    }

    public OrderbookLevelInfos GetOrderInfos() {
        ordersLock.lock();
        try {
            List<LevelInfo> bidInfos = new ArrayList<>(orders.size());
            List<LevelInfo> askInfos = new ArrayList<>(orders.size());

            for (Map.Entry<Integer, Deque<Order>> entry : bids.entrySet()) {
                bidInfos.add(CreateLevelInfos(entry.getKey(), entry.getValue()));
            }

            for (Map.Entry<Integer, Deque<Order>> entry : asks.entrySet()) {
                askInfos.add(CreateLevelInfos(entry.getKey(), entry.getValue()));
            }

            return new OrderbookLevelInfos(bidInfos, askInfos);
        } finally {
            ordersLock.unlock();
        }
    }

    public List<OrderDetails> GetOrderDetails() {
        ordersLock.lock();
        try {
            List<OrderDetails> details = new ArrayList<>(orders.size());
            appendOrderDetails(bids, details);
            appendOrderDetails(asks, details);
            return List.copyOf(details);
        } finally {
            ordersLock.unlock();
        }
    }

    private static void appendOrderDetails(NavigableMap<Integer, Deque<Order>> book, List<OrderDetails> details) {
        for (Map.Entry<Integer, Deque<Order>> entry : book.entrySet()) {
            for (Order order : entry.getValue()) {
                details.add(new OrderDetails(
                    order.GetOrderId(),
                    order.getUserId(),
                    order.getTicker(),
                    order.GetSide(),
                    order.GetOrderType(),
                    displayPrice(priceKey(order), order),
                    order.GetRemainingQuantity()));
            }
        }
    }

    private static LevelInfo CreateLevelInfos(int price, Deque<Order> orders) {
        int quantity = 0;
        for (Order order : orders) {
            quantity += order.GetRemainingQuantity();
        }
        Order sample = orders.peekFirst();
        double displayPrice = sample != null ? displayPrice(price, sample) : price;
        return new LevelInfo(displayPrice, quantity);
    }

    @Override
    public void close() {
        shutdown.set(true);

        ordersLock.lock();
        try {
            shutdownCondition.signalAll();
        } finally {
            ordersLock.unlock();
        }

        ordersPruneThread.interrupt();
        try {
            ordersPruneThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
