package loanengine.engine;

import loanengine.core.LoanException;
import loanengine.dates.DayCount;
import loanengine.model.Contract;
import loanengine.model.Prepayment;
import loanengine.model.Prepayment.RecastMode;
import loanengine.rate.RateResolver;
import loanengine.rate.ResolvedRate;
import loanengine.rate.ResetSpec;
import loanengine.schedule.AmortizationType;
import loanengine.schedule.ScheduleGenerator;
import loanengine.schedule.SchedulePeriod;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CashflowEngine {

    private static final int MONEY_SCALE = Contract.MONEY_SCALE;
    private static final int WORK_SCALE = 10;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE);

    public CashflowEngine() {
    }

    public List<CashflowRow> generate(Contract contract) {
        List<SchedulePeriod> schedule = ScheduleGenerator.generate(contract);
        return generate(contract, schedule);
    }

    public List<CashflowRow> applyFixingCorrection(Contract contract,
                                                   LocalDate firstAffectedResetDate,
                                                   List<CashflowRow> previous) {
        List<SchedulePeriod> schedule = ScheduleGenerator.generate(contract);
        List<CashflowRow> full = generate(contract, schedule);
        if (previous == null || previous.isEmpty()) {
            return full;
        }
        int unaffected = 0;
        for (CashflowRow row : previous) {
            SchedulePeriod period = schedule.get(row.period() - 1);
            LocalDate resetDate = period.resetDate();
            if (resetDate != null && resetDate.isBefore(firstAffectedResetDate)) {
                unaffected++;
            } else {
                break;
            }
        }
        List<CashflowRow> merged = new ArrayList<>();
        for (int i = 0; i < unaffected; i++) {
            merged.add(previous.get(i));
        }
        for (int i = unaffected; i < full.size(); i++) {
            merged.add(full.get(i));
        }
        return List.copyOf(merged);
    }

    private List<CashflowRow> generate(Contract contract, List<SchedulePeriod> schedule) {
        AmortizationType type = contract.amortizationType();
        Map<Integer, Prepayment> prepayByPeriod = mapPrepayments(contract, schedule);

        BigDecimal opening = ZERO;
        ResolvedRate currentRate = initialRate(contract.reset());
        BigDecimal levelPayment = ZERO;
        BigDecimal equalInstallment = ZERO;
        LocalDate appliedResetDate = null;

        List<CashflowRow> rows = new ArrayList<>();
        for (SchedulePeriod period : schedule) {
            ResolvedRate rate = resolvePeriodRate(contract.reset(), period, currentRate);
            currentRate = rate;

            boolean first = period.period() == 1;
            BigDecimal openingBalance = first ? ZERO : opening;
            BigDecimal advance = first ? money(contract.notional()) : ZERO;
            BigDecimal startBalance = openingBalance.add(advance);

            Prepayment prepaymentEvent = prepayByPeriod.get(period.period());
            boolean recast = prepaymentEvent != null;
            int remaining = schedule.size() - period.period() + 1;

            boolean newReset = period.resetDate() != null
                    && !period.resetDate().equals(appliedResetDate);
            if (period.resetDate() != null) {
                appliedResetDate = period.resetDate();
            }

            if (first || newReset) {
                if (type == AmortizationType.EQUAL_PAYMENT) {
                    levelPayment = levelPayment(startBalance, rate.rate(),
                            factor(contract, period), remaining);
                }
                if (type == AmortizationType.EQUAL_PRINCIPAL) {
                    equalInstallment = startBalance.divide(BigDecimal.valueOf(remaining),
                            MONEY_SCALE, MONEY_ROUNDING);
                }
            }
            BigDecimal interest = interest(startBalance, rate.rate(), contract.dayCount(),
                    period.accrualStart(), period.accrualEnd());

            BigDecimal prepayAmount = prepaymentEvent == null
                    ? ZERO
                    : money(prepaymentEvent.amount()).min(startBalance);

            BigDecimal scheduledPrincipal = scheduledPrincipal(type,
                    startBalance.subtract(prepayAmount), interest,
                    equalInstallment, levelPayment,
                    period.period() == schedule.size());

            if (recast && prepaymentEvent.mode() == RecastMode.KEEP_TERM
                    && type == AmortizationType.EQUAL_PAYMENT) {
                BigDecimal postPrepayBalance = money(
                        startBalance.subtract(prepayAmount).subtract(scheduledPrincipal));
                SchedulePeriod nextPeriodFactor = remaining > 1
                        ? schedule.get(period.period())
                        : period;
                levelPayment = levelPayment(postPrepayBalance, rate.rate(),
                        factor(contract, nextPeriodFactor), remaining - 1);
            }

            BigDecimal closing = money(startBalance.subtract(scheduledPrincipal)
                    .subtract(prepayAmount));
            if (closing.signum() < 0) {
                throw new LoanException(LoanException.Category.NEGATIVE_PRINCIPAL,
                        "negative closing balance in period " + period.period());
            }
            BigDecimal totalPayment = interest.add(scheduledPrincipal).add(prepayAmount);

            rows.add(new CashflowRow(period.period(), period.accrualStart(),
                    period.accrualEnd(), period.paymentDate(), openingBalance, advance,
                    rate.rate(), interest, scheduledPrincipal, prepayAmount, totalPayment,
                    closing, rate.version(), recast));
            opening = closing;

            if (closing.signum() == 0 && period.period() < schedule.size()) {
                break;
            }
        }
        verifyFullyRepaid(opening, schedule, rows);
        return List.copyOf(rows);
    }

    private static void verifyFullyRepaid(BigDecimal lastClosing,
                                          List<SchedulePeriod> schedule,
                                          List<CashflowRow> rows) {
        CashflowRow lastRow = rows.get(rows.size() - 1);
        boolean endsAtMaturity = lastRow.period() == schedule.size();
        if (lastClosing.signum() != 0 && endsAtMaturity) {
            throw new LoanException(LoanException.Category.SCALE_OVERFLOW,
                    "unrepaid tail balance at maturity: " + lastClosing);
        }
    }

    private static ResolvedRate initialRate(ResetSpec reset) {
        if (!reset.isFloating()) {
            return RateResolver.resolve(reset, null);
        }
        return RateResolver.resolve(reset, reset.resetDates().get(0));
    }

    private static ResolvedRate resolvePeriodRate(ResetSpec reset, SchedulePeriod period,
                                                  ResolvedRate current) {
        if (period.resetDate() == null) {
            return current;
        }
        return RateResolver.resolve(reset, period.resetDate());
    }

    private static BigDecimal interest(BigDecimal principal, BigDecimal annualRate,
                                       DayCount dayCount, LocalDate start, LocalDate end) {
        BigDecimal factor = dayCount.yearFraction(start, end);
        BigDecimal raw = principal.multiply(annualRate).multiply(factor);
        return raw.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }

    private static BigDecimal scheduledPrincipal(AmortizationType type, BigDecimal balance,
                                                 BigDecimal interest,
                                                 BigDecimal equalInstallment,
                                                 BigDecimal levelPayment, boolean last) {
        BigDecimal planned;
        switch (type) {
            case BULLET -> planned = last ? balance : ZERO;
            case EQUAL_PRINCIPAL -> planned = last ? balance : equalInstallment.min(balance);
            case EQUAL_PAYMENT -> planned = last ? balance : levelPayment.subtract(interest)
                    .min(balance);
            default -> throw new IllegalStateException("unknown amortization type");
        }
        planned = money(planned);
        if (planned.signum() < 0) {
            planned = ZERO;
        }
        if (planned.compareTo(balance) > 0) {
            planned = balance;
        }
        if (last) {
            planned = balance;
        }
        return planned;
    }

    private static BigDecimal factor(Contract contract, SchedulePeriod period) {
        if (period.period() == 1) {
            LocalDate start = contract.valueDate();
            LocalDate end = contract.frequency().shift(start, 1);
            return contract.dayCount().yearFraction(start, end);
        }
        return contract.dayCount().yearFraction(period.accrualStart(),
                period.accrualEnd());
    }

    private static BigDecimal levelPayment(BigDecimal balance, BigDecimal annualRate,
                                           BigDecimal factor, int remaining) {
        if (remaining <= 0) {
            return ZERO;
        }
        BigDecimal periodRate = annualRate.multiply(factor);
        if (periodRate.signum() == 0) {
            return balance.divide(BigDecimal.valueOf(remaining), MONEY_SCALE, MONEY_ROUNDING);
        }
        BigDecimal low = ZERO;
        BigDecimal high = balance.add(balance);
        for (int i = 0; i < 60; i++) {
            BigDecimal mid = low.add(high).divide(BigDecimal.valueOf(2),
                    WORK_SCALE, RoundingMode.HALF_UP);
            BigDecimal residual = simulateResidual(balance, mid, periodRate, remaining);
            if (residual.signum() > 0) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return high.setScale(MONEY_SCALE, RoundingMode.CEILING);
    }

    private static BigDecimal simulateResidual(BigDecimal balance, BigDecimal payment,
                                               BigDecimal periodRate, int remaining) {
        BigDecimal current = balance;
        for (int i = 0; i < remaining; i++) {
            BigDecimal accrued = current.multiply(periodRate)
                    .setScale(MONEY_SCALE, MONEY_ROUNDING);
            BigDecimal principal = payment.subtract(accrued);
            if (principal.signum() < 0) {
                principal = ZERO;
            }
            if (principal.compareTo(current) > 0) {
                principal = current;
            }
            current = current.subtract(principal);
        }
        return current;
    }

    private static Map<Integer, Prepayment> mapPrepayments(Contract contract,
                                                           List<SchedulePeriod> schedule) {
        Map<Integer, Prepayment> mapped = new HashMap<>();
        for (Prepayment prepayment : contract.prepayments()) {
            int found = -1;
            for (SchedulePeriod period : schedule) {
                LocalDate date = prepayment.valueDate();
                boolean inside = (!date.isBefore(period.accrualStart()))
                        && date.isBefore(period.accrualEnd());
                boolean onMaturity = date.equals(period.accrualEnd())
                        && period.period() == schedule.size();
                if (inside || onMaturity) {
                    found = period.period();
                    break;
                }
            }
            if (found < 0) {
                throw new LoanException(LoanException.Category.INVALID_DATE,
                        "prepayment date outside schedule accrual windows: "
                                + prepayment.valueDate());
            }
            mapped.put(found, prepayment);
        }
        return mapped;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }
}
