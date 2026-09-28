package loancashflow.engine;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Fixed-scale decimal conventions shared by every computation step. */
public final class Money {

    public static final int MONEY_SCALE = 2;
    public static final int RATE_SCALE = 10;
    public static final int FRACTION_SCALE = 10;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    private Money() {
    }

    /** Monetary amounts always settle at exactly two decimal places. */
    public static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, ROUNDING);
    }

    /** Rates are annual decimal fractions (0.05 for 5%) held at ten decimal places. */
    public static BigDecimal rate(BigDecimal value) {
        return value.setScale(RATE_SCALE, ROUNDING);
    }

    /** Year fractions are held at ten decimal places. */
    public static BigDecimal fraction(BigDecimal value) {
        return value.setScale(FRACTION_SCALE, ROUNDING);
    }

    /**
     * Interest = balance * rate * fraction. Rounding steps are explicit:
     * accrual factor (rate*fraction) to 10dp HALF_UP, then interest to 2dp HALF_UP.
     */
    public static BigDecimal interest(BigDecimal balance, BigDecimal rate, BigDecimal fraction) {
        BigDecimal accrualFactor = rate.multiply(fraction).setScale(FRACTION_SCALE, ROUNDING);
        return balance.multiply(accrualFactor).setScale(MONEY_SCALE, ROUNDING);
    }

    /** Reject monetary inputs carrying sub-cent precision instead of silently rounding. */
    public static void requireMoneyScale(String field, BigDecimal value) {
        if (value != null && value.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw new LoanException(ErrorCode.SCALE_OVERFLOW,
                    field + " exceeds the fixed money scale of " + MONEY_SCALE
                            + " decimal places: " + value.toPlainString());
        }
    }
}
