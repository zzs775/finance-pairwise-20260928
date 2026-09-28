package fi;

import java.time.LocalDate;

public final class BusinessDayAdjuster {

    private BusinessDayAdjuster() {
    }

    public static LocalDate adjust(LocalDate date, BusinessDayConvention convention, HolidayCalendar calendar) {
        return switch (convention) {
            case UNADJUSTED -> date;
            case FOLLOWING -> nextBusinessDay(date, calendar);
            case PRECEDING -> previousBusinessDay(date, calendar);
            case MODIFIED_FOLLOWING -> {
                LocalDate candidate = nextBusinessDay(date, calendar);
                if (candidate.getMonth() != date.getMonth()) {
                    candidate = previousBusinessDay(date, calendar);
                }
                yield candidate;
            }
        };
    }

    public static LocalDate nextBusinessDay(LocalDate date, HolidayCalendar calendar) {
        LocalDate candidate = date;
        while (calendar.isHoliday(candidate)) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    public static LocalDate previousBusinessDay(LocalDate date, HolidayCalendar calendar) {
        LocalDate candidate = date;
        while (calendar.isHoliday(candidate)) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }
}
