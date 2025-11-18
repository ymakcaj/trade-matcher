package tradeMatcher;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory account repository with token based authentication.
 */
public final class AccountManager {
    private final ConcurrentHashMap<String, UserAccount> accountsById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UserAccount> accountsByToken = new ConcurrentHashMap<>();

    public UserAccount registerAccount(String userId, double startingCash, Map<String, Long> startingPositions, boolean admin) {
        UserAccount account = UserAccount.createWithGeneratedApiKey(userId, startingCash, startingPositions, admin);
        storeAccount(account);
        return account;
    }

    public UserAccount registerAccountWithApiKey(String userId, String apiKey, double startingCash, Map<String, Long> startingPositions, boolean admin) {
        Objects.requireNonNull(apiKey, "apiKey");
        UserAccount existingWithToken = accountsByToken.get(apiKey);
        if (existingWithToken != null && !existingWithToken.getUserId().equals(userId)) {
            throw new IllegalArgumentException("API token already assigned to another user");
        }
        UserAccount account = UserAccount.createWithApiKey(userId, apiKey, startingCash, startingPositions, admin);
        storeAccount(account);
        return account;
    }

    public Optional<UserAccount> findByToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(accountsByToken.get(token));
    }

    public UserAccount requireByToken(String token) {
        return findByToken(token).orElseThrow(() -> new IllegalArgumentException("Invalid API token"));
    }

    public Optional<UserAccount> findById(String userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(accountsById.get(userId));
    }

    public Collection<UserAccount> getAllAccounts() {
        return accountsById.values();
    }

    private void storeAccount(UserAccount account) {
        Objects.requireNonNull(account, "account");
        UserAccount previous = accountsById.put(account.getUserId(), account);
        if (previous != null) {
            accountsByToken.remove(previous.getApiKey());
        }
        accountsByToken.put(account.getApiKey(), account);
    }

    public void ensureSufficientBuyingPower(UserAccount account, String ticker, double price, long quantity) {
        Objects.requireNonNull(account, "account");
        double requiredCash = price * quantity;
        if (!account.hasSufficientCash(requiredCash)) {
            throw new IllegalArgumentException("INSUFFICIENT_FUNDS");
        }
    }

    public void ensureSufficientInventory(UserAccount account, String ticker, long quantity) {
        Objects.requireNonNull(account, "account");
        if (!account.hasInventory(ticker, quantity)) {
            throw new IllegalArgumentException("INSUFFICIENT_INVENTORY");
        }
    }
}
