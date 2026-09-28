package loanengine.dates;

import java.time.LocalDate;

public enum DayConvention {
    NONE,
    FOLLOWING,
    MODIFIED_FOLLOWING;

    public LocalDate adjust(LocalDate date, BusinessDayCalendar calendar) {
        return switch (this) {
            case NONE -> date;
            case FOLLOWING -> following(date, calendar);
            case MODIFIED_FOLLOWING -> {
                LocalDate adjusted = following(date, calendar);
                if (adjusted.getMonth() != date.getMonth()
                        || adjusted.getYear() != date.getYear()) {
                    adjusted = previous(date, calendar);
                }
                yield adjusted;
            }
        };
    }

    private static LocalDate following(LocalDate date, BusinessDayCalendar calendar) {
        LocalDate candidate = date;
        while (!calendar.isBusinessDay(candidate)) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    private static LocalDate previous(LocalDate date, BusinessDayCalendar calendar) {
        LocalDate candidate = date;
        while (!calendar.isBusinessDay(candidate)) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }
}
