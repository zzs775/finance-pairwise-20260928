package fi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class ExDividend {

    private ExDividend() {
    }

    public static List<ExDividendWindow> windows(CashFlowSchedule schedule) {
        BondTerms terms = schedule.terms();
        int count = terms.exDividendBusinessDays();
        List<ExDividendWindow> result = new ArrayList<>();
        for (AccrualPeriod period : schedule.periods()) {
            LocalDate ex = period.unadjustedEnd();
            if (count > 0) {
                int remaining = count;
                while (remaining > 0) {
                    ex = ex.minusDays(1);
                    if (terms.calendar().isBusinessDay(ex)) {
                        remaining--;
                    }
                }
            }
            result.add(new ExDividendWindow(period.index(),
                    period.unadjustedEnd(), period.paymentDate(), ex));
        }
        return List.copyOf(result);
    }

    public static SettlementInfo settle(CashFlowSchedule schedule, LocalDate settlementDate) {
        fi.model.DailyAccrual daily =
                AccrualCalculator.accruedOn(schedule.terms(), schedule, settlementDate);
        AccrualPeriod period = schedule.periods().get(daily.periodIndex() - 1);
        return new SettlementInfo(
                settlementDate,
                period.index(),
                period.unadjustedStart(),
                period.unadjustedEnd(),
                daily.accruedAmount(),
                daily.nextCouponPayableToBuyer());
    }

    static ExDividendWindow windowFor(CashFlowSchedule schedule, int periodIndex) {
        for (ExDividendWindow window : windows(schedule)) {
            if (window.periodIndex() == periodIndex) {
                return window;
            }
        }
        return null;
    }
}
