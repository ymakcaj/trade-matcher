package tradeMatcher;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Represents the integer scaling used to convert between external decimal prices
 * and the matching engine's integer based representation.
 */
public final class PriceScale {
    private final int precision;
    private final int scaleFactor;

    private PriceScale(int precision, int scaleFactor) {
        if (precision < 0) {
            throw new IllegalArgumentException("precision must be non-negative");
        }
        if (scaleFactor <= 0) {
            throw new IllegalArgumentException("scaleFactor must be positive");
        }
        this.precision = precision;
        this.scaleFactor = scaleFactor;
    }

    public static PriceScale fromPrecision(int precision) {
        int scale = (int) Math.pow(10, precision);
        return new PriceScale(precision, scale);
    }

    public int precision() {
        return precision;
    }

    public int scaleFactor() {
        return scaleFactor;
    }

    public int toBookPrice(double decimalPrice) {
        if (decimalPrice == 0.0d) {
            return 0;
        }
        try {
            BigDecimal bd = BigDecimal.valueOf(decimalPrice).setScale(precision, RoundingMode.UNNECESSARY);
            bd = bd.movePointRight(precision);
            return bd.intValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Price does not align with precision " + precision + ": " + decimalPrice, ex);
        }
    }

    public double toDisplayPrice(int bookPrice) {
        BigDecimal bd = BigDecimal.valueOf(bookPrice).movePointLeft(precision);
        return bd.doubleValue();
    }

    public boolean isAligned(double decimalPrice) {
        try {
            toBookPrice(decimalPrice);
            return true;
        } catch (ArithmeticException ex) {
            return false;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
