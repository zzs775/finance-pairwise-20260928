package fi;

import fi.model.DailyAccrual;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class AccrualCalculator {

    static final int MONEY_SCALE = 2;
    static final int RATE_SCALE = 6;
    static final int FRACTION_SCALE = 10;

    public record Segment(
            LocalDate start,
            LocalDate end,
            BigDecimal notional,
            BigDecimal annualRate
    ) {
    }

    private AccrualCalculator() {
    }

    public static BigDecimal couponAmount(BigDecimal notional, BigDecimal annualRate, BigDecimal yearFraction) {
        BigDecimal rate = annualRate.setScale(RATE_SCALE, RoundingMode.HALF_UP);
        BigDecimal fraction = yearFraction.setScale(FRACTION_SCALE, RoundingMode.HALF_UP);
        return notional.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                .multiply(rate)
                .multiply(fraction)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public static DailyAccrual accruedOn(BondTerms terms, CashFlowSchedule schedule, LocalDate settlementDate) {
        AccrualPeriod period = periodContaining(schedule, settlementDate);
        BigDecimal accrued = accruedUntil(terms,
                period.unadjustedStart(), period.unadjustedEnd(), settlementDate);

        ExDividendWindow window = windowFor(schedule, period.index());
        boolean ex = window != null && !settlementDate.isBefore(window.exDate());
        BigDecimal nextCoupon = ex ? BigDecimal.ZERO.setScale(MONEY_SCALE)
                : period.couponAmount();
        return new DailyAccrual(terms, settlementDate, period.index(),
                accrued, nextCoupon, !ex);
    }

    /**
     * Splits an accrual interval at every in-force rate or notional step date.
     * Each segment carries a single fixed-scale rate and a single notional.
     */
    static List<Segment> segments(BondTerms terms, LocalDate start, LocalDate end) {
        List<LocalDate> cuts = new ArrayList<>();
        for (RateStep step : terms.rateSteps()) {
            LocalDate date = step.startDate();
            if (date.isAfter(start) && date.isBefore(end)) {
                cuts.add(date);
            }
        }
        for (NotionalStep step : terms.notionalSteps()) {
            LocalDate date = step.startDate();
            if (date.isAfter(start) && date.isBefore(end)) {
                cuts.add(date);
            }
        }
        cuts.sort(LocalDate::compareTo);
        List<LocalDate> uniqueCuts = new ArrayList<>();
        for (LocalDate cut : cuts) {
            if (uniqueCuts.isEmpty()
                    || !uniqueCuts.get(uniqueCuts.size() - 1).equals(cut)) {
                uniqueCuts.add(cut);
            }
        }

        List<Segment> segments = new ArrayList<>();
        LocalDate cursor = start;
        for (LocalDate cut : uniqueCuts) {
            segments.add(new Segment(cursor, cut,
                    ScheduleGenerator.notionalAt(terms, cursor),
                    ScheduleGenerator.rateAt(terms, cursor)));
            cursor = cut;
        }
        segments.add(new Segment(cursor, end,
                ScheduleGenerator.notionalAt(terms, cursor),
                ScheduleGenerator.rateAt(terms, cursor)));
        return segments;
    }

    static BigDecimal segmentCoupon(BondTerms terms, Segment segment) {
        BigDecimal fraction = terms.dayCount().yearFraction(segment.start(), segment.end());
        return couponAmount(segment.notional(), segment.annualRate(), fraction);
    }

    static BigDecimal periodCoupon(BondTerms terms, LocalDate start, LocalDate end) {
        BigDecimal total = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
        for (Segment segment : segments(terms, start, end)) {
            total = total.add(segmentCoupon(terms, segment));
        }
        return total;
    }

    /**
     * Accrued amount at {@code date} within [start,end): completed segments
     * are counted at their fixed coupon, the active segment accrues day by day.
     * At {@code date == end} this equals the full period coupon exactly.
     */
    static BigDecimal accruedUntil(BondTerms terms, LocalDate start,
                                   LocalDate end, LocalDate date) {
        if (date.isBefore(start) || date.isAfter(end)) {
            throw new ScheduleException(ErrorCode.DATE_ORDER,
                    "accrual date " + date + " outside [" + start + "," + end + "]");
        }
        BigDecimal total = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
        for (Segment segment : segments(terms, start, end)) {
            if (!date.isBefore(segment.end())) {
                total = total.add(segmentCoupon(terms, segment));
            } else if (date.isAfter(segment.start())) {
                BigDecimal fraction = terms.dayCount()
                        .yearFraction(segment.start(), date);
                total = total.add(couponAmount(
                        segment.notional(), segment.annualRate(), fraction));
                break;
            } else {
                break;
            }
        }
        return total;
    }

    static AccrualPeriod periodContaining(CashFlowSchedule schedule, LocalDate date) {
        for (AccrualPeriod period : schedule.periods()) {
            if (!date.isBefore(period.unadjustedStart())
                    && date.isBefore(period.unadjustedEnd())) {
                return period;
            }
        }
        AccrualPeriod last = schedule.periods().get(schedule.periods().size() - 1);
        if (date.equals(last.unadjustedEnd())) {
            return last;
        }
        throw new ScheduleException(ErrorCode.DATE_ORDER,
                "settlement date " + date + " outside schedule");
    }

    private static ExDividendWindow windowFor(CashFlowSchedule schedule, int periodIndex) {
        return ExDividend.windows(schedule).stream()
                .filter(w -> w.periodIndex() == periodIndex)
                .findFirst()
                .orElse(null);
    }
}
