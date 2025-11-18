package tradeMatcher;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates monotonically increasing numeric order identifiers.
 */
public final class OrderIdGenerator {
    private final AtomicLong sequence;

    public OrderIdGenerator() {
        this(1L);
    }

    public OrderIdGenerator(long initialValue) {
        if (initialValue <= 0L) {
            throw new IllegalArgumentException("initialValue must be positive");
        }
        this.sequence = new AtomicLong(initialValue);
    }

    public String nextId() {
        long value = sequence.getAndIncrement();
        return Long.toString(value);
    }
}
