package tradeMatcher;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Broadcasts public market data snapshots and incremental updates to connected clients.
 */
public final class PublicFeedService {
    private static final Logger LOG = LoggerFactory.getLogger(PublicFeedService.class);

    private final Set<Session> sessions = new CopyOnWriteArraySet<>();
    private final Map<String, Map<String, Integer>> lastBidLevels = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> lastAskLevels = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();

    public void register(Session session) {
        sessions.add(session);
    }

    public void unregister(Session session) {
        sessions.remove(session);
    }

    public void sendSnapshot(Session session, String ticker, OrderbookLevelInfos snapshot) {
        Objects.requireNonNull(session, "session");
        Map<String, Object> payload = Map.of(
                "type", "SNAPSHOT",
                "ticker", ticker,
                "bids", snapshot.GetBids(),
                "asks", snapshot.GetAsks());
        try {
            session.getRemote().sendString(gson.toJson(payload));
        } catch (Exception ex) {
            LOG.warn("Failed to send snapshot", ex);
        }
    }

    public void broadcastSnapshot(String ticker, OrderbookLevelInfos snapshot) {
        Map<String, Object> payload = Map.of(
                "type", "SNAPSHOT",
                "ticker", ticker,
                "bids", snapshot.GetBids(),
                "asks", snapshot.GetAsks());
        sendToAll(payload);
        lastBidLevels.put(ticker, toLevelMap(snapshot.GetBids()));
        lastAskLevels.put(ticker, toLevelMap(snapshot.GetAsks()));
    }

    public void broadcastDelta(String ticker, OrderbookLevelInfos snapshot) {
        Map<String, Integer> previousBids = lastBidLevels.computeIfAbsent(ticker, __ -> new HashMap<>());
        Map<String, Integer> previousAsks = lastAskLevels.computeIfAbsent(ticker, __ -> new HashMap<>());
        Map<String, Integer> currentBids = toLevelMap(snapshot.GetBids());
        Map<String, Integer> currentAsks = toLevelMap(snapshot.GetAsks());

        if (previousBids.isEmpty() && previousAsks.isEmpty()) {
            broadcastSnapshot(ticker, snapshot);
            return;
        }

        List<List<String>> changes = new ArrayList<>();
        collectChanges("BUY", previousBids, currentBids, changes);
        collectChanges("SELL", previousAsks, currentAsks, changes);

        if (changes.isEmpty()) {
            return;
        }

        Map<String, Object> payload = Map.of(
                "type", "LOB_UPDATE",
                "ticker", ticker,
                "changes", changes);
        sendToAll(payload);

        lastBidLevels.put(ticker, currentBids);
        lastAskLevels.put(ticker, currentAsks);
    }

    public void broadcastTrades(String ticker, List<Trade> trades) {
        if (trades == null || trades.isEmpty()) {
            return;
        }
        Map<String, Object> payload = Map.of(
                "type", "TRADES",
                "ticker", ticker,
                "data", trades);
        sendToAll(payload);
    }

    private void collectChanges(String side, Map<String, Integer> previous, Map<String, Integer> current, List<List<String>> out) {
        Map<String, Integer> snapshot = new HashMap<>(current);
        for (Map.Entry<String, Integer> entry : previous.entrySet()) {
            snapshot.putIfAbsent(entry.getKey(), entry.getValue());
        }

        for (Map.Entry<String, Integer> entry : snapshot.entrySet()) {
            String price = entry.getKey();
            int newQty = current.getOrDefault(price, 0);
            int oldQty = previous.getOrDefault(price, 0);
            if (newQty != oldQty) {
                out.add(List.of(side, price, Integer.toString(newQty)));
            }
        }
    }

    private Map<String, Integer> toLevelMap(List<LevelInfo> levels) {
        Map<String, Integer> map = new HashMap<>();
        if (levels == null) {
            return map;
        }
        for (LevelInfo level : levels) {
            String price = formatPrice(level.getPrice());
            map.put(price, level.getQuantity());
        }
        return map;
    }

    private static String formatPrice(double price) {
        return String.format(Locale.US, "%.3f", price);
    }

    private void sendToAll(Map<String, Object> payload) {
        if (sessions.isEmpty()) {
            return;
        }
        String json = gson.toJson(payload);
        for (Session session : sessions) {
            try {
                session.getRemote().sendString(json);
            } catch (Exception ex) {
                LOG.warn("Failed to broadcast public feed update", ex);
            }
        }
    }
}
