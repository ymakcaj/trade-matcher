package tradeMatcher;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents an authenticated user of the trading engine.
 */
public final class UserAccount {
    private final String userId;
    private final String apiKey;
    private final boolean admin;
    private final ConcurrentHashMap<String, Long> positions = new ConcurrentHashMap<>();
    private double cashBalance;

    public static UserAccount create(String userId, double startingCash, Map<String, Long> startingPositions, boolean admin) {
        Objects.requireNonNull(userId, "userId");
        UserAccount account = new UserAccount(userId, generateApiKey(), admin);
        account.cashBalance = startingCash;
        if (startingPositions != null) {
            startingPositions.forEach((ticker, qty) -> {
                if (qty != null && qty != 0L) {
                    account.positions.put(ticker.toUpperCase(), qty);
                }
            });
        }
        return account;
    }

    private static String generateApiKey() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private UserAccount(String userId, String apiKey, boolean admin) {
        this.userId = userId;
        this.apiKey = apiKey;
        this.admin = admin;
    }

    public String getUserId() {
        return userId;
    }

    public String getApiKey() {
        return apiKey;
    }

    public boolean isAdmin() {
        return admin;
    }

    public synchronized double getCashBalance() {
        return cashBalance;
    }

    public synchronized void adjustCash(double delta) {
        cashBalance += delta;
    }

    public synchronized boolean hasSufficientCash(double requiredCash) {
        return cashBalance >= requiredCash;
    }

    public Map<String, Long> snapshotPositions() {
        return Collections.unmodifiableMap(positions);
    }

    public long getPosition(String ticker) {
        if (ticker == null) {
            return 0L;
        }
        return positions.getOrDefault(ticker.toUpperCase(), 0L);
    }

    public void adjustPosition(String ticker, long delta) {
        if (ticker == null || delta == 0L) {
            return;
        }
        positions.merge(ticker.toUpperCase(), delta, (existing, change) -> {
            long updated = existing + change;
            return updated == 0L ? null : updated;
        });
    }

    public boolean hasInventory(String ticker, long requiredQty) {
        return getPosition(ticker) >= requiredQty;
    }
}
