package tradeMatcher;

/**
 * Central place for shared price scaling metadata.
 */
public final class PriceScaleProvider {
    private static final PriceScaleRegistry REGISTRY = createRegistry();

    private PriceScaleProvider() {
    }

    private static PriceScaleRegistry createRegistry() {
        PriceScaleRegistry registry = new PriceScaleRegistry(3);
        registry.registerScale("TEST", 3);
        registry.registerScale("DEMO", 3);
        return registry;
    }

    public static PriceScaleRegistry getRegistry() {
        return REGISTRY;
    }
}
