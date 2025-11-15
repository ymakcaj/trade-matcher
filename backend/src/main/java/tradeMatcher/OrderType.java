package tradeMatcher;

/**
 * Defines the execution logic of an order independent from any lifetime policy.
 */
public enum OrderType {

    /**
     * An aggressive order that executes immediately against the best available liquidity.
     */
    MARKET,

    /**
     * A passive order that rests on the book at a specified price until matched or canceled.
     */
    LIMIT,

    /**
     * A conditional order that converts into a {@link #MARKET} order once a trigger price is reached.
     */
    STOP_MARKET,

    /**
     * A conditional order that converts into a {@link #LIMIT} order once a trigger price is reached.
     */
    STOP_LIMIT
}
