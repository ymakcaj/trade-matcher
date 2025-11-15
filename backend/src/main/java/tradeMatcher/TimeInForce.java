package tradeMatcher;

/**
 * Captures the lifetime semantics applied to non-market orders when they rest on the order book.
 */
public enum TimeInForce {

    /**
     * Good 'Til Cancel: the order remains active until it is either fully filled or explicitly canceled.
     */
    GTC,

    /**
     * Good For Day: the order is automatically canceled at the close of the current trading session.
     */
    DAY,

    /**
     * Immediate Or Cancel: execute whatever quantity is available immediately and cancel any remainder.
     */
    IOC,

    /**
     * Fill Or Kill: the order must execute in full immediately or be canceled without resting on the book.
     */
    FOK
}
