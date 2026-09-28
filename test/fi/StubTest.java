package fi;

import java.time.LocalDate;
import java.util.List;

public final class StubTest {

    private StubTest() {
    }

    static void run(Tests.Asserts a) {
        boundaries(a, SampleBonds.regular(),
                List.of("2025-01-15", "2025-07-15", "2026-01-15",
                        "2026-07-15", "2027-01-15"));

        boundaries(a, SampleBonds.shortFinal(),
                List.of("2025-01-15", "2025-04-15", "2025-07-15",
                        "2025-10-15", "2026-01-15", "2026-04-15",
                        "2026-07-15", "2026-10-15", "2026-11-30"));

        boundaries(a, SampleBonds.longInitial(),
                List.of("2025-01-20", "2025-04-30", "2025-07-30",
                        "2025-10-30", "2026-01-30", "2026-04-30",
                        "2026-07-31"));

        boundaries(a, SampleBonds.eomAmortizing(),
                List.of("2024-02-29", "2024-08-31", "2025-02-28",
                        "2025-08-31", "2026-02-28"));

        eomFebruary(a);
        shortInitial(a);
        longFinal(a);
    }

    private static void boundaries(Tests.Asserts a, BondTerms terms,
                                   List<String> expected) {
        CashFlowSchedule schedule = ScheduleGenerator.generate(terms);
        List<AccrualPeriod> periods = schedule.periods();
        a.equal(terms.id() + " period count",
                periods.size(), expected.size() - 1);
        for (int i = 0; i < expected.size(); i++) {
            LocalDate actual = i == 0
                    ? periods.get(0).unadjustedStart()
                    : periods.get(i - 1).unadjustedEnd();
            a.equal(terms.id() + " boundary " + i,
                    actual.toString(), expected.get(i));
        }
        for (int i = 1; i < periods.size(); i++) {
            a.that(terms.id() + " contiguous " + i,
                    periods.get(i).unadjustedStart()
                            .equals(periods.get(i - 1).unadjustedEnd()));
        }
    }

    private static void eomFebruary(Tests.Asserts a) {
        BondTerms terms = new BondTerms(
                "EOM-LEAP",
                LocalDate.of(2024, 1, 31),
                LocalDate.of(2025, 7, 31),
                Frequency.SEMI_ANNUAL,
                StubType.NONE,
                DayCount.ACT_360,
                true,
                BusinessDayConvention.UNADJUSTED,
                0,
                List.of(new RateStep(LocalDate.of(2024, 1, 31),
                        new java.math.BigDecimal("0.05"))),
                List.of(new NotionalStep(LocalDate.of(2024, 1, 31),
                        new java.math.BigDecimal("1000.00"))),
                HolidayCalendar.of("E"));
        CashFlowSchedule schedule = ScheduleGenerator.generate(terms);
        a.equal("eom first boundary",
                schedule.periods().get(0).unadjustedStart().toString(),
                "2024-01-31");
        a.equal("eom february leap",
                schedule.periods().get(0).unadjustedEnd().toString(),
                "2024-07-31");
        a.equal("eom feb non-leap end",
                schedule.periods().get(1).unadjustedEnd().toString(),
                "2025-01-31");
    }

    private static void shortInitial(Tests.Asserts a) {
        BondTerms terms = new BondTerms(
                "SHORT-INIT",
                LocalDate.of(2025, 2, 10),
                LocalDate.of(2026, 7, 31),
                Frequency.QUARTERLY,
                StubType.SHORT_INITIAL,
                DayCount.ACT_360,
                false,
                BusinessDayConvention.UNADJUSTED,
                0,
                List.of(new RateStep(LocalDate.of(2025, 2, 10),
                        new java.math.BigDecimal("0.04"))),
                List.of(new NotionalStep(LocalDate.of(2025, 2, 10),
                        new java.math.BigDecimal("1000.00"))),
                HolidayCalendar.of("S"));
        CashFlowSchedule schedule = ScheduleGenerator.generate(terms);
        a.equal("short initial first end",
                schedule.periods().get(0).unadjustedEnd().toString(),
                "2025-04-30");
        a.that("short initial shorter than quarter",
                schedule.periods().get(0).yearFraction()
                        .compareTo(new java.math.BigDecimal("0.25")) < 0);
    }

    private static void longFinal(Tests.Asserts a) {
        BondTerms terms = new BondTerms(
                "LONG-FINAL",
                LocalDate.of(2025, 1, 31),
                LocalDate.of(2025, 11, 20),
                Frequency.QUARTERLY,
                StubType.LONG_FINAL,
                DayCount.ACT_360,
                true,
                BusinessDayConvention.UNADJUSTED,
                0,
                List.of(new RateStep(LocalDate.of(2025, 1, 31),
                        new java.math.BigDecimal("0.04"))),
                List.of(new NotionalStep(LocalDate.of(2025, 1, 31),
                        new java.math.BigDecimal("1000.00"))),
                HolidayCalendar.of("L"));
        CashFlowSchedule schedule = ScheduleGenerator.generate(terms);
        AccrualPeriod last =
                schedule.periods().get(schedule.periods().size() - 1);
        a.equal("long final start",
                last.unadjustedStart().toString(), "2025-07-31");
        a.equal("long final end",
                last.unadjustedEnd().toString(), "2025-11-20");
        a.that("long final longer than quarter",
                last.yearFraction()
                        .compareTo(new java.math.BigDecimal("0.25")) > 0);
    }
}
