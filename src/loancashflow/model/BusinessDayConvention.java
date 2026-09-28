package loancashflow.model;

import java.time.LocalDate;

/** Business-day adjustment applied to payment dates only. */
public enum BusinessDayConvention {
    NONE,
    FOLLOWING,
    MODIFIED_FOLLOWING;

    public LocalDate adjust(LocalDate date, HolidayCalendar calendar) {
        return switch (this) {
            case NONE -> date;
            case FOLLOWING -> rollForward(date, calendar);
            case MODIFIED_FOLLOWING -> {
                LocalDate forward = rollForward(date, calendar);
                if (forward.getMonth() != date.getMonth()) {
                    yield rollBackward(date, calendar);
                }
                yield forward;
            }
        };
    }

    private static LocalDate rollForward(LocalDate date, HolidayCalendar calendar) {
        LocalDate candidate = date;
        while (!calendar.isBusinessDay(candidate)) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    private static LocalDate rollBackward(LocalDate date, HolidayCalendar calendar) {
        LocalDate candidate = date;
        while (!calendar.isBusinessDay(candidate)) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }
}
