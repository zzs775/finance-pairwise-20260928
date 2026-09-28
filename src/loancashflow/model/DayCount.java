package loancashflow.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Day-count conventions used to derive the accrual fraction of a year. */
public enum DayCount {
    ACT_365F,
    ACT_360,
    THIRTY_360;

    public BigDecimal yearFraction(LocalDate start, LocalDate end) {
        long numerator;
        long denominator;
        switch (this) {
            case ACT_365F -> {
                numerator = java.time.temporal.ChronoUnit.DAYS.between(start, end);
                denominator = 365L;
            }
            case ACT_360 -> {
                numerator = java.time.temporal.ChronoUnit.DAYS.between(start, end);
                denominator = 360L;
            }
            case THIRTY_360 -> {
                numerator = thirty360Days(start, end);
                denominator = 360L;
            }
            default -> throw new IllegalStateException("unknown day count " + this);
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator),
                loancashflow.engine.Money.FRACTION_SCALE, loancashflow.engine.Money.ROUNDING);
    }

    /** Bond-basis 30/360 with the standard end-of-February adjustment. */
    private static long thirty360Days(LocalDate start, LocalDate end) {
        int d1 = start.getDayOfMonth();
        int d2 = end.getDayOfMonth();
        if (d1 == 31) {
            d1 = 30;
        }
        if (d2 == 31 && d1 == 30) {
            d2 = 30;
        }
        return 360L * (end.getYear() - start.getYear())
                + 30L * (end.getMonthValue() - start.getMonthValue())
                + (d2 - d1);
    }
}
