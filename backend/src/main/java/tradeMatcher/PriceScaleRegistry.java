package tradeMatcher;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides access to {@link PriceScale} instances per ticker symbol.
 */
public final class PriceScaleRegistry {
    private final Map<String, PriceScale> scales = new ConcurrentHashMap<>();
    private final int defaultPrecision;

    public PriceScaleRegistry(int defaultPrecision) {
        if (defaultPrecision < 0) {
            throw new IllegalArgumentException("defaultPrecision must be non-negative");
        }
        this.defaultPrecision = defaultPrecision;
    }

    public PriceScale getScale(String ticker) {
        String key = normalizeKey(ticker);
        return scales.computeIfAbsent(key, __ -> PriceScale.fromPrecision(defaultPrecision));
    }

    public void registerScale(String ticker, int precision) {
        scales.put(normalizeKey(ticker), PriceScale.fromPrecision(precision));
    }

    private static String normalizeKey(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return "";
        }
        return ticker.toUpperCase(Locale.ROOT);
    }
}
