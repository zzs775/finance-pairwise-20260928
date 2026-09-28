package fi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class ScheduleGenerator {

    private ScheduleGenerator() {
    }

    public static CashFlowSchedule generate(BondTerms terms) {
        TermsValidator.validate(terms);
        List<LocalDate> boundaries = UnadjustedSchedule.boundaries(terms);

        List<AccrualPeriod> periods = new ArrayList<>();
        List<CashFlow> cashFlows = new ArrayList<>();
        BigDecimal priorNotional =
                terms.notionalSteps().get(0).outstandingNotional();

        for (int i = 0; i < boundaries.size() - 1; i++) {
            LocalDate start = boundaries.get(i);
            LocalDate end = boundaries.get(i + 1);
            if (!end.isAfter(start)) {
                throw new ScheduleException(ErrorCode.DATE_ORDER,
                        "non-ascending boundary at " + start);
            }
            BigDecimal notional = notionalAt(terms, start);
            BigDecimal rate = rateAt(terms, start);
            BigDecimal yearFraction = terms.dayCount().yearFraction(start, end);
            BigDecimal coupon = AccrualCalculator.periodCoupon(terms, start, end);
            java.util.List<AccrualSegment> segmentRecords = new java.util.ArrayList<>();
            for (AccrualCalculator.Segment segment
                    : AccrualCalculator.segments(terms, start, end)) {
                BigDecimal segmentFraction =
                        terms.dayCount().yearFraction(segment.start(), segment.end());
                segmentRecords.add(new AccrualSegment(
                        segment.start(),
                        segment.end(),
                        segment.notional().setScale(2, RoundingMode.HALF_UP),
                        segment.annualRate().setScale(6, RoundingMode.HALF_UP),
                        segmentFraction,
                        AccrualCalculator.segmentCoupon(terms, segment)));
            }

            LocalDate paymentDate = BusinessDayAdjuster.adjust(
                    end, terms.convention(), terms.calendar());

            AccrualPeriod period = new AccrualPeriod(
                    i + 1, start, end, paymentDate,
                    notional.setScale(2, RoundingMode.HALF_UP),
                    rate.setScale(6, RoundingMode.HALF_UP),
                    yearFraction, coupon, segmentRecords);
            periods.add(period);
            if (coupon.signum() > 0) {
                cashFlows.add(new CashFlow(paymentDate, CashFlowType.COUPON,
                        coupon, period.index()));
            }

            BigDecimal endNotional = notionalAt(terms, end);
            BigDecimal principal = priorNotional.subtract(endNotional);
            if (principal.signum() > 0) {
                cashFlows.add(new CashFlow(paymentDate, CashFlowType.PRINCIPAL,
                        principal.setScale(2, RoundingMode.HALF_UP), period.index()));
            }
            priorNotional = endNotional;
        }

        cashFlows.sort(CashFlow::compareTo);
        return new CashFlowSchedule(terms, List.copyOf(periods), List.copyOf(cashFlows));
    }

    static BigDecimal rateAt(BondTerms terms, LocalDate date) {
        BigDecimal result = terms.rateSteps().get(0).annualRate();
        for (RateStep step : terms.rateSteps()) {
            if (!step.startDate().isAfter(date)) {
                result = step.annualRate();
            } else {
                break;
            }
        }
        return result;
    }

    static BigDecimal notionalAt(BondTerms terms, LocalDate date) {
        BigDecimal result = terms.notionalSteps().get(0).outstandingNotional();
        for (NotionalStep step : terms.notionalSteps()) {
            if (!step.startDate().isAfter(date)) {
                result = step.outstandingNotional();
            } else {
                break;
            }
        }
        return result;
    }
}
