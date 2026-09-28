package fi;

import java.time.LocalDate;

public final class ExDividendTest {

    private ExDividendTest() {
    }

    static void run(Tests.Asserts a) {
        CashFlowSchedule schedule =
                ScheduleGenerator.generate(SampleBonds.regular());
        ExDividendWindow first = ExDividend.windows(schedule).get(0);

        a.equal("window based on unadjusted end",
                first.periodEndUnadjusted(), LocalDate.of(2025, 7, 15));
        a.equal("ex date three business days before end",
                first.exDate(), LocalDate.of(2025, 7, 10));

        LocalDate exDate = first.exDate();

        SettlementInfo cumOneDayBefore =
                ExDividend.settle(schedule, exDate.minusDays(1));
        a.that("one day before ex: buyer receives coupon",
                cumOneDayBefore.nextCouponPayableToBuyer());

        SettlementInfo onEx = ExDividend.settle(schedule, exDate);
        a.that("on ex date: seller retains coupon",
                !onEx.nextCouponPayableToBuyer());

        SettlementInfo oneDayAfter =
                ExDividend.settle(schedule, exDate.plusDays(1));
        a.that("one day after ex: seller retains coupon",
                !oneDayAfter.nextCouponPayableToBuyer());

        SettlementInfo lastCumDay =
                ExDividend.settle(schedule, LocalDate.of(2025, 7, 14));
        a.that("day before period end remains ex",
                !lastCumDay.nextCouponPayableToBuyer());

        a.that("accrual grows toward period end cum-dividend",
                oneDayAfter.accruedAmount()
                        .compareTo(cumOneDayBefore.accruedAmount()) > 0);

        holidayWindow(a);
    }

    private static void holidayWindow(Tests.Asserts a) {
        BondTerms terms = new BondTerms(
                "EX-HOL",
                LocalDate.of(2025, 1, 2),
                LocalDate.of(2025, 7, 2),
                Frequency.SEMI_ANNUAL,
                StubType.NONE,
                DayCount.ACT_360,
                false,
                BusinessDayConvention.UNADJUSTED,
                5,
                java.util.List.of(new RateStep(LocalDate.of(2025, 1, 2),
                        new java.math.BigDecimal("0.05"))),
                java.util.List.of(new NotionalStep(LocalDate.of(2025, 1, 2),
                        new java.math.BigDecimal("1000.00"))),
                SampleBonds.calendar());
        CashFlowSchedule schedule = ScheduleGenerator.generate(terms);
        ExDividendWindow window = ExDividend.windows(schedule).get(0);
        a.that("ex date counts business days only",
                terms.calendar().isBusinessDay(window.exDate()));
        a.equal("ex date skips weekends and holidays",
                window.exDate(), LocalDate.of(2025, 6, 25));
    }
}
