package fi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ScheduleTest {

    private ScheduleTest() {
    }

    static void run(Tests.Asserts a) {
        CashFlowSchedule regular =
                ScheduleGenerator.generate(SampleBonds.regular());

        a.equal("regular period count", regular.periods().size(), 4);
        a.equal("regular cashflow count", regular.cashFlows().size(), 4);
        a.equal("first coupon",
                regular.periods().get(0).couponAmount().toPlainString(),
                "25138.89");
        a.equal("second coupon across year",
                regular.periods().get(1).couponAmount().toPlainString(),
                "25555.56");

        a.that("cash flows sorted by date", isSorted(regular));

        paymentAdjustmentIndependent(a, regular);

        BigDecimal total = BigDecimal.ZERO;
        for (AccrualPeriod period : regular.periods()) {
            total = total.add(period.couponAmount());
        }
        a.money("regular total coupon", total, "101388.90");
    }

    private static boolean isSorted(CashFlowSchedule schedule) {
        for (int i = 1; i < schedule.cashFlows().size(); i++) {
            if (schedule.cashFlows().get(i).paymentDate()
                    .isBefore(schedule.cashFlows().get(i - 1).paymentDate())) {
                return false;
            }
        }
        return true;
    }

    private static void paymentAdjustmentIndependent(Tests.Asserts a,
                                                     CashFlowSchedule adjusted) {
        BondTerms terms = adjusted.terms();
        BondTerms unadjustedTerms = new BondTerms(
                terms.id(), terms.valueDate(), terms.maturityDate(),
                terms.frequency(), terms.stubType(), terms.dayCount(),
                terms.eom(), BusinessDayConvention.UNADJUSTED,
                terms.exDividendBusinessDays(), terms.rateSteps(),
                terms.notionalSteps(), terms.calendar());
        CashFlowSchedule unadjusted =
                ScheduleGenerator.generate(unadjustedTerms);

        for (int i = 0; i < adjusted.periods().size(); i++) {
            AccrualPeriod left = adjusted.periods().get(i);
            AccrualPeriod right = unadjusted.periods().get(i);
            a.that("accrual start unchanged by payment convention " + i,
                    left.unadjustedStart().equals(right.unadjustedStart()));
            a.that("accrual end unchanged by payment convention " + i,
                    left.unadjustedEnd().equals(right.unadjustedEnd()));
            a.that("coupon unchanged by payment convention " + i,
                    left.couponAmount().compareTo(right.couponAmount()) == 0);
            a.that("payment date is business day " + i,
                    terms.calendar().isBusinessDay(left.paymentDate()));
        }
    }
}
