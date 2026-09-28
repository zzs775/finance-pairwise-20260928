package fi;

import java.time.LocalDate;

public enum BusinessDayConvention {
    UNADJUSTED,
    FOLLOWING,
    MODIFIED_FOLLOWING,
    PRECEDING;

    public LocalDate adjust(LocalDate date, HolidayCalendar calendar) {
        return BusinessDayAdjuster.adjust(date, this, calendar);
    }
}
