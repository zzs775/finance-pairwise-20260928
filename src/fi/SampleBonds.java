package fi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Frozen in-spec synthetic bonds used by the CLI and the test oracle.
 */
public final class SampleBonds {

    public static final String CALENDAR_NAME = "SPEC-CAL";

    private SampleBonds() {
    }

    public static HolidayCalendar calendar() {
        return HolidayCalendar.of(CALENDAR_NAME,
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 4, 4),
                LocalDate.of(2025, 5, 1),
                LocalDate.of(2025, 10, 1),
                LocalDate.of(2025, 12, 25),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 4, 3),
                LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 12, 25),
                LocalDate.of(2027, 1, 1));
    }

    public static BondTerms regular() {
        return new BondTerms(
                "REGULAR-2Y",
                LocalDate.of(2025, 1, 15),
                LocalDate.of(2027, 1, 15),
                Frequency.SEMI_ANNUAL,
                StubType.NONE,
                DayCount.ACT_360,
                false,
                BusinessDayConvention.MODIFIED_FOLLOWING,
                3,
                List.of(new RateStep(LocalDate.of(2025, 1, 15),
                        new BigDecimal("0.050000"))),
                List.of(new NotionalStep(LocalDate.of(2025, 1, 15),
                        new BigDecimal("1000000.00"))),
                calendar());
    }

    public static BondTerms shortFinal() {
        return new BondTerms(
                "SHORT-FINAL",
                LocalDate.of(2025, 1, 15),
                LocalDate.of(2026, 11, 30),
                Frequency.QUARTERLY,
                StubType.SHORT_FINAL,
                DayCount.ACT_365F,
                false,
                BusinessDayConvention.FOLLOWING,
                2,
                List.of(new RateStep(LocalDate.of(2025, 1, 15),
                        new BigDecimal("0.040000"))),
                List.of(new NotionalStep(LocalDate.of(2025, 1, 15),
                        new BigDecimal("500000.00"))),
                calendar());
    }

    public static BondTerms longInitial() {
        return new BondTerms(
                "LONG-INITIAL",
                LocalDate.of(2025, 1, 20),
                LocalDate.of(2026, 7, 31),
                Frequency.QUARTERLY,
                StubType.LONG_INITIAL,
                DayCount.THIRTY_360,
                false,
                BusinessDayConvention.PRECEDING,
                2,
                List.of(new RateStep(LocalDate.of(2025, 1, 20),
                        new BigDecimal("0.060000"))),
                List.of(new NotionalStep(LocalDate.of(2025, 1, 20),
                        new BigDecimal("750000.00"))),
                calendar());
    }

    public static BondTerms eomAmortizing() {
        return new BondTerms(
                "EOM-AMORT",
                LocalDate.of(2024, 2, 29),
                LocalDate.of(2026, 2, 28),
                Frequency.SEMI_ANNUAL,
                StubType.SHORT_INITIAL,
                DayCount.ACT_360,
                true,
                BusinessDayConvention.MODIFIED_FOLLOWING,
                3,
                List.of(
                        new RateStep(LocalDate.of(2024, 2, 29),
                                new BigDecimal("0.050000")),
                        new RateStep(LocalDate.of(2025, 8, 31),
                                new BigDecimal("0.045000"))),
                List.of(
                        new NotionalStep(LocalDate.of(2024, 2, 29),
                                new BigDecimal("1000000.00")),
                        new NotionalStep(LocalDate.of(2025, 2, 28),
                                new BigDecimal("700000.00")),
                        new NotionalStep(LocalDate.of(2025, 8, 31),
                                new BigDecimal("400000.00"))),
                HolidayCalendar.of(CALENDAR_NAME,
                        LocalDate.of(2024, 12, 31),
                        LocalDate.of(2025, 12, 31)));
    }

    public static BondTerms byId(String id) {
        return switch (id) {
            case "regular" -> regular();
            case "short-final" -> shortFinal();
            case "long-initial" -> longInitial();
            case "eom-amort" -> eomAmortizing();
            default -> throw new IllegalArgumentException("unknown sample: " + id);
        };
    }

    public static List<String> ids() {
        return List.of("regular", "short-final", "long-initial", "eom-amort");
    }
}
