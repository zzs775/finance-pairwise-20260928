package loanengine.dates;

import java.math.BigDecimal;
import java.time.LocalDate;

public enum DayCount {
    ACT_365F,
    ACT_360,
    THIRTY_360;

    public static final int FACTOR_SCALE = 10;

    public BigDecimal yearFraction(LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start)) {
            throw new IllegalArgumentException("invalid accrual window: " + start + " to " + end);
        }
        long days = switch (this) {
            case ACT_365F, ACT_360 -> java.time.temporal.ChronoUnit.DAYS.between(start, end);
            case THIRTY_360 -> thirty360Days(start, end);
        };
        int basis = this == ACT_360 || this == THIRTY_360 ? 360 : 365;
        return BigDecimal.valueOf(days)
                .divide(BigDecimal.valueOf(basis), FACTOR_SCALE, java.math.RoundingMode.HALF_UP);
    }

    private static long thirty360Days(LocalDate start, LocalDate end) {
        int d1 = start.getDayOfMonth();
        int d2 = end.getDayOfMonth();
        if (d1 == 31) {
            d1 = 30;
        }
        if (d2 == 31 && d1 == 30) {
            d2 = 30;
        }
        long yearDays = 360L * (end.getYear() - start.getYear());
        long monthDays = 30L * (end.getMonthValue() - start.getMonthValue());
        return yearDays + monthDays + (d2 - d1);
    }
}
