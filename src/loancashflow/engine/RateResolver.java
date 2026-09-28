package loancashflow.engine;

import loancashflow.model.Contract;
import loancashflow.model.RateSpec;
import loancashflow.model.ResetSpec;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Selects the per-period rate: fixed, or versioned fixing + spread clipped to floor/cap. */
public final class RateResolver {

    private RateResolver() {
    }

    /** Reset date governing the period (reset in effect on the accrual start). */
    public static LocalDate governingResetDate(Contract contract, Period period) {
        ResetSpec reset = contract.rateSpec().reset();
        LocalDate firstReset = reset.firstResetDate();
        LocalDate accrualStart = period.accrualStart();
        if (!accrualStart.isAfter(firstReset)) {
            return firstReset;
        }
        long elapsedMonths = java.time.temporal.ChronoUnit.MONTHS.between(
                firstReset, accrualStart);
        long resetStepMonths = reset.frequency().monthsPerPeriod();
        long resetIndex = java.lang.Math.floorDiv(elapsedMonths, resetStepMonths);
        return firstReset.plusMonths(resetIndex * resetStepMonths);
    }

    public static BigDecimal resolve(Contract contract, Period period) {
        RateSpec spec = contract.rateSpec();
        if (!spec.floating()) {
            return Money.rate(spec.fixedRate());
        }
        LocalDate resetDate = governingResetDate(contract, period);
        BigDecimal raw = contract.fixingTable().latest(resetDate);
        if (raw == null) {
            throw new LoanException(ErrorCode.MISSING_FIXING,
                    "no fixing published for reset date " + resetDate
                            + " governing period " + period.ordinal()
                            + " (" + period.accrualStart() + " to " + period.accrualEnd() + ")");
        }
        BigDecimal rate = Money.rate(raw).add(spec.spread());
        if (spec.floor() != null && rate.compareTo(spec.floor()) < 0) {
            rate = spec.floor();
        }
        if (spec.cap() != null && rate.compareTo(spec.cap()) > 0) {
            rate = spec.cap();
        }
        return Money.rate(rate);
    }
}
