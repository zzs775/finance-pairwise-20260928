package fi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

public enum DayCount {
    ACT_360,
    ACT_365F,
    THIRTY_360;

    public BigDecimal yearFraction(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new ScheduleException(ErrorCode.DATE_ORDER,
                    "null dates passed to day count");
        }
        if (end.isBefore(start)) {
            throw new ScheduleException(ErrorCode.DATE_ORDER,
                    "day count end before start: " + start + " > " + end);
        }
        return switch (this) {
            case ACT_360 -> fraction(actualDays(start, end), 360);
            case ACT_365F -> fraction(actualDays(start, end), 365);
            case THIRTY_360 -> fraction(thirty360Days(start, end), 360);
        };
    }

    private static BigDecimal fraction(long numerator, long denominator) {
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 10, RoundingMode.HALF_UP);
    }

    static long actualDays(LocalDate start, LocalDate end) {
        return java.time.temporal.ChronoUnit.DAYS.between(start, end);
    }

    static long thirty360Days(LocalDate start, LocalDate end) {
        int startDay = start.getDayOfMonth();
        int endDay = end.getDayOfMonth();
        if (startDay == 31) {
            startDay = 30;
        }
        if (endDay == 31 && (startDay == 30 || startDay == 31)) {
            endDay = 30;
        }
        return 360L * (end.getYear() - start.getYear())
                + 30L * (end.getMonthValue() - start.getMonthValue())
                + (endDay - startDay);
    }
}
