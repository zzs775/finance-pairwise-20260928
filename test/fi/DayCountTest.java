package fi;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class DayCountTest {

    private DayCountTest() {
    }

    static void run(Tests.Asserts a) {
        LocalDate start = LocalDate.of(2025, 1, 15);
        LocalDate end = LocalDate.of(2025, 7, 15);
        a.equal("act/360 days",
                DayCount.ACT_360.yearFraction(start, end),
                new BigDecimal("0.5027777778"));
        a.equal("act/365f days",
                DayCount.ACT_365F.yearFraction(start, end),
                new BigDecimal("0.4958904110"));

        a.equal("30/360 half year",
                DayCount.THIRTY_360.yearFraction(start, end),
                new BigDecimal("0.5000000000"));
        a.equal("30/360 jan31 rollback",
                DayCount.THIRTY_360.yearFraction(
                        LocalDate.of(2025, 1, 31),
                        LocalDate.of(2025, 2, 28)),
                new BigDecimal("0.0777777778"));

        a.that("leap year actual days",
                DayCount.actualDays(
                        LocalDate.of(2024, 2, 28),
                        LocalDate.of(2024, 3, 1)) == 2);
        a.that("non-leap year actual days",
                DayCount.actualDays(
                        LocalDate.of(2025, 2, 28),
                        LocalDate.of(2025, 3, 1)) == 1);

        a.throwsCode("reversed dates", ErrorCode.DATE_ORDER,
                () -> DayCount.ACT_360.yearFraction(end, start));
        a.equal("zero length fraction",
                DayCount.ACT_360.yearFraction(start, start),
                BigDecimal.ZERO.setScale(10, java.math.RoundingMode.HALF_UP));
    }
}
