package tradeMatcher;

import java.time.Instant;

/**
 * Immutable representation of a single fill executed by the matching engine.
 */
public record FillRecord(
        String fillId,
        String orderId,
        String userId,
        String ticker,
        OrderSide side,
        double price,
        int quantity,
        Instant timestamp) {
}
