package loanengine;

import loanengine.dates.BusinessDayCalendar;
import loanengine.dates.DayConvention;
import loanengine.dates.DayCount;
import loanengine.dates.Frequency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static loanengine.TestFramework.assertEquals;
import static loanengine.TestFramework.check;

public final class DatesTest {

    private DatesTest() {
    }

    public static void run() {
        LocalDate start = LocalDate.of(2025, 1, 15);
        LocalDate end = LocalDate.of(2025, 2, 15);
        assertEquals(0, new BigDecimal("31")
                .divide(new BigDecimal("365"), 10, java.math.RoundingMode.HALF_UP)
                .compareTo(DayCount.ACT_365F.yearFraction(start, end)),
                "ACT/365F Jan15->Feb15 = 31/365");

        assertEquals(0, new BigDecimal("30")
                .divide(new BigDecimal("360"), 10, java.math.RoundingMode.HALF_UP)
                .compareTo(DayCount.THIRTY_360.yearFraction(start, end)),
                "30/360 Jan15->Feb15 = 30/360");

        LocalDate leapStart = LocalDate.of(2024, 2, 28);
        LocalDate leapEnd = LocalDate.of(2024, 3, 1);
        assertEquals(0, new BigDecimal("2")
                .divide(new BigDecimal("365"), 10, java.math.RoundingMode.HALF_UP)
                .compareTo(DayCount.ACT_365F.yearFraction(leapStart, leapEnd)),
                "ACT/365F counts 2 leap-day-window days over fixed 365");

        BusinessDayCalendar cal = new BusinessDayCalendar(
                Set.of(LocalDate.of(2025, 5, 1)));
        check(!cal.isBusinessDay(LocalDate.of(2025, 5, 3)), "Saturday not business day");
        check(!cal.isBusinessDay(LocalDate.of(2025, 5, 1)), "holiday not business day");
        assertEquals(LocalDate.of(2025, 5, 2),
                DayConvention.FOLLOWING.adjust(LocalDate.of(2025, 5, 1), cal),
                "following skips holiday and weekend");
        assertEquals(LocalDate.of(2025, 5, 30),
                DayConvention.MODIFIED_FOLLOWING.adjust(LocalDate.of(2025, 5, 31), cal),
                "modified following rolls back into same month");

        assertEquals(LocalDate.of(2025, 3, 31),
                Frequency.MONTHLY.shift(LocalDate.of(2025, 1, 31), 2),
                "plain month shift caps day");
    }
}
