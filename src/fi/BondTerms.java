package fi;

import java.time.LocalDate;
import java.util.List;

public record BondTerms(
        String id,
        LocalDate valueDate,
        LocalDate maturityDate,
        Frequency frequency,
        StubType stubType,
        DayCount dayCount,
        boolean eom,
        BusinessDayConvention convention,
        int exDividendBusinessDays,
        List<RateStep> rateSteps,
        List<NotionalStep> notionalSteps,
        HolidayCalendar calendar
) {
}
