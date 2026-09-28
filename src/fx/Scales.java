package fx;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Fixed scales and arithmetic conventions used everywhere in the engine. */
public final class Scales {
    public static final int RATE_SCALE = 8;
    public static final int DF_SCALE = 10;
    public static final int MONEY_SCALE = 2;
    public static final MathContext MC = MathContext.DECIMAL64;

    /** Any operand or intermediate result beyond this magnitude is an overflow. */
    public static final BigDecimal MAX_MAGNITUDE = new BigDecimal("1E18");

    /** Fixed-minor-unit reconciliation tolerance between the two valuation methods. */
    public static final BigDecimal MONEY_TOLERANCE = new BigDecimal("0.01");

    private Scales() {
    }

    public static BigDecimal rate(BigDecimal v) {
        return checked(v).setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal df(BigDecimal v) {
        return checked(v).setScale(DF_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal money(BigDecimal v) {
        return checked(v).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Rejects null, NaN-like and excessively large magnitudes as arithmetic overflow. */
    public static BigDecimal checked(BigDecimal v) {
        if (v == null) {
            throw new FxException(Failure.ARITHMETIC_OVERFLOW, "null numeric operand");
        }
        BigDecimal abs = v.abs();
        if (abs.compareTo(MAX_MAGNITUDE) > 0) {
            throw new FxException(Failure.ARITHMETIC_OVERFLOW,
                    "numeric magnitude overflow: " + v.toPlainString());
        }
        return v;
    }

    /** add with overflow guard, returned at the scale of the two operands. */
    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        checked(a);
        checked(b);
        return checked(a.add(b));
    }

    /** subtract with overflow guard. */
    public static BigDecimal sub(BigDecimal a, BigDecimal b) {
        checked(a);
        checked(b);
        return checked(a.subtract(b));
    }

    /** multiply with overflow guard. */
    public static BigDecimal mul(BigDecimal a, BigDecimal b) {
        checked(a);
        checked(b);
        return checked(a.multiply(b));
    }

    /** divide with overflow guard and the fixed engine math context. */
    public static BigDecimal div(BigDecimal a, BigDecimal b) {
        checked(a);
        checked(b);
        if (b.signum() == 0) {
            throw new FxException(Failure.ARITHMETIC_OVERFLOW, "division by zero");
        }
        return checked(a.divide(b, MC));
    }
}
