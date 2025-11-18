package tradeMatcher;

import com.google.gson.Gson;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages authenticated private WebSocket connections and pushes account-specific events.
 */
public final class PrivateFeedService {
    private static final Logger LOG = LoggerFactory.getLogger(PrivateFeedService.class);

    private final ConcurrentHashMap<String, Set<Session>> sessionsByUser = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();

    public void register(Session session, String userId) {
        sessionsByUser.compute(userId, (key, existing) -> {
            Set<Session> set = existing == null ? new CopyOnWriteArraySet<>() : existing;
            set.add(session);
            return set;
        });
    }

    public void unregister(Session session, String userId) {
        if (userId == null) {
            return;
        }
        sessionsByUser.computeIfPresent(userId, (key, set) -> {
            set.remove(session);
            return set.isEmpty() ? null : set;
        });
    }

    public void sendAcknowledgement(String userId, String orderId, String clientOrderId) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "ACK");
        payload.put("orderId", orderId);
        if (clientOrderId != null && !clientOrderId.isBlank()) {
            payload.put("clientOrderId", clientOrderId);
        }
        payload.put("timestamp", Instant.now().toString());
        send(userId, payload);
    }

    public void sendReject(String userId, String orderId, String clientOrderId, String reason) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "REJECT");
        payload.put("orderId", orderId);
        if (clientOrderId != null && !clientOrderId.isBlank()) {
            payload.put("clientOrderId", clientOrderId);
        }
        payload.put("reason", reason);
        payload.put("timestamp", Instant.now().toString());
        send(userId, payload);
    }

    public void sendFill(FillRecord fill) {
        send(fill.userId(), Map.of(
                "type", "FILL",
                "orderId", fill.orderId(),
                "fillId", fill.fillId(),
                "side", fill.side().name(),
                "price", fill.price(),
                "quantity", fill.quantity(),
                "ticker", fill.ticker(),
                "timestamp", fill.timestamp().toString()));
    }

    public void sendCanceled(String userId, String orderId) {
        send(userId, Map.of(
                "type", "CANCELED",
                "orderId", orderId,
                "timestamp", Instant.now().toString()));
    }

    private void send(String userId, Map<String, Object> payload) {
        if (userId == null) {
            return;
        }
        Set<Session> sessions = sessionsByUser.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        String json = gson.toJson(payload);
        for (Session session : sessions) {
            try {
                session.getRemote().sendString(json);
            } catch (Exception ex) {
                LOG.warn("Failed to send private message to user {}", userId, ex);
            }
        }
    }
}
