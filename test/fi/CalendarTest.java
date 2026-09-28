package fi;

import java.time.LocalDate;

public final class CalendarTest {

    private CalendarTest() {
    }

    static void run(Tests.Asserts a) {
        HolidayCalendar calendar = HolidayCalendar.of("T",
                LocalDate.of(2025, 5, 1));
        a.that("weekend saturday holiday",
                calendar.isHoliday(LocalDate.of(2025, 5, 3)));
        a.that("weekend sunday holiday",
                calendar.isHoliday(LocalDate.of(2025, 5, 4)));
        a.that("listed holiday",
                calendar.isHoliday(LocalDate.of(2025, 5, 1)));
        a.that("normal business day",
                calendar.isBusinessDay(LocalDate.of(2025, 5, 2)));

        a.equal("following skips weekend",
                BusinessDayConvention.FOLLOWING.adjust(
                        LocalDate.of(2025, 5, 3), calendar),
                LocalDate.of(2025, 5, 5));
        a.equal("preceding skips weekend",
                BusinessDayConvention.PRECEDING.adjust(
                        LocalDate.of(2025, 5, 4), calendar),
                LocalDate.of(2025, 5, 2));

        HolidayCalendar monthEnd = SampleBonds.calendar();
        a.equal("modified following month rollback",
                BusinessDayConvention.MODIFIED_FOLLOWING.adjust(
                        LocalDate.of(2025, 5, 31), monthEnd),
                LocalDate.of(2025, 5, 30));
        a.equal("unadjusted unchanged",
                BusinessDayConvention.UNADJUSTED.adjust(
                        LocalDate.of(2025, 5, 31), monthEnd),
                LocalDate.of(2025, 5, 31));
    }
}
