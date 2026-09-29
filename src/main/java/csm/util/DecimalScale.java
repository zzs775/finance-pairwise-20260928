package csm.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 全引擎统一的 BigDecimal 尺度：所有金额在进入模型前归一化到该尺度。
 */
public final class DecimalScale {

    public static final int SCALE = 8;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private DecimalScale() {
    }

    public static BigDecimal normalize(BigDecimal value) {
        requireNonNull(value, "value");
        return value.setScale(SCALE, ROUNDING);
    }

    public static BigDecimal requireNonNull(BigDecimal value, String name) {
        if (value == null) {
            throw new NullPointerException(name + " must not be null");
        }
        return value;
    }

    public static boolean isZero(BigDecimal value) {
        return value.signum() == 0;
    }

    public static BigDecimal max(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    public static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
