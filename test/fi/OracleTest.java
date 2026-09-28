package fi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class OracleTest {

    private OracleTest() {
    }

    static void run(Tests.Asserts a) {
        for (String id : SampleBonds.ids()) {
            sampleOracle(a, SampleBonds.byId(id));
        }
        steppedCouponOracle(a);
    }

    private static void sampleOracle(Tests.Asserts a, BondTerms terms) {
        CashFlowSchedule schedule = ScheduleGenerator.generate(terms);

        for (AccrualPeriod period : schedule.periods()) {
            BigDecimal segmentSum = BigDecimal.ZERO;
            for (AccrualSegment segment : period.segments()) {
                segmentSum = segmentSum.add(segment.couponAmount());
            }
            a.money(terms.id() + " segment sum equals period coupon p"
                            + period.index(),
                    segmentSum, period.couponAmount().toPlainString());

            BigDecimal startAccrual = AccrualCalculator.accruedUntil(
                    terms, period.unadjustedStart(), period.unadjustedEnd(),
                    period.unadjustedStart());
            a.money(terms.id() + " accrual zero at start p" + period.index(),
                    startAccrual, "0.00");

            BigDecimal endAccrual = AccrualCalculator.accruedUntil(
                    terms, period.unadjustedStart(), period.unadjustedEnd(),
                    period.unadjustedEnd());
            a.money(terms.id() + " accrual equals coupon at end p"
                            + period.index(),
                    endAccrual, period.couponAmount().toPlainString());

            dailyMonotone(a, terms, period);
        }
    }

    private static void dailyMonotone(Tests.Asserts a, BondTerms terms,
                                      AccrualPeriod period) {
        BigDecimal previous = null;
        LocalDate cursor = period.unadjustedStart();
        while (!cursor.isAfter(period.unadjustedEnd())) {
            BigDecimal accrued = AccrualCalculator.accruedUntil(
                    terms, period.unadjustedStart(), period.unadjustedEnd(),
                    cursor);
            if (previous != null) {
                a.that(terms.id() + " daily accrual non-decreasing p"
                                + period.index() + " " + cursor,
                        accrued.compareTo(previous) >= 0);
            }
            previous = accrued;
            cursor = cursor.plusDays(1);
        }
    }

    private static void steppedCouponOracle(Tests.Asserts a) {
        BondTerms terms = new BondTerms(
                "STEP-RATE",
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2026, 1, 1),
                Frequency.SEMI_ANNUAL,
                StubType.NONE,
                DayCount.ACT_360,
                false,
                BusinessDayConvention.UNADJUSTED,
                0,
                List.of(
                        new RateStep(LocalDate.of(2025, 1, 1),
                                new BigDecimal("0.040000")),
                        new RateStep(LocalDate.of(2025, 9, 1),
                                new BigDecimal("0.060000"))),
                List.of(new NotionalStep(LocalDate.of(2025, 1, 1),
                        new BigDecimal("1000000.00"))),
                HolidayCalendar.of("O"));
        CashFlowSchedule schedule = ScheduleGenerator.generate(terms);
        AccrualPeriod second = schedule.periods().get(1);
        a.equal("second period split into two segments",
                second.segments().size(), 2);

        BigDecimal manual = BigDecimal.ZERO;
        manual = manual.add(coupon(terms,
                LocalDate.of(2025, 7, 1), LocalDate.of(2025, 9, 1),
                "0.040000"));
        manual = manual.add(coupon(terms,
                LocalDate.of(2025, 9, 1), LocalDate.of(2026, 1, 1),
                "0.060000"));
        a.money("manual stepped coupon equals generated",
                second.couponAmount(), manual.toPlainString());

        BigDecimal endAccrual = AccrualCalculator.accruedUntil(
                terms, second.unadjustedStart(), second.unadjustedEnd(),
                second.unadjustedEnd());
        a.money("stepped oracle endpoint equals coupon",
                endAccrual, second.couponAmount().toPlainString());
    }

    private static BigDecimal coupon(BondTerms terms, LocalDate start,
                                     LocalDate end, String rate) {
        BigDecimal fraction = terms.dayCount().yearFraction(start, end);
        return AccrualCalculator.couponAmount(
                new BigDecimal("1000000.00"), new BigDecimal(rate), fraction);
    }
}
