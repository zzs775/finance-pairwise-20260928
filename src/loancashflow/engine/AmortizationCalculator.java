package loancashflow.engine;

import loancashflow.model.Contract;
import loancashflow.model.CashFlowRow;
import loancashflow.model.Prepayment;
import loancashflow.model.RecastMode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Per-method principal/interest plan and annuity payment solving. */
public final class AmortizationCalculator {

    private AmortizationCalculator() {
    }

    /** Scheduled per-period payment for an annuity, scaled to money. */
    public static BigDecimal annuityPayment(BigDecimal nominal, List<BigDecimal> periodRates,
                                            List<BigDecimal> yearFractions) {
        return recastPayment(nominal, periodRates, yearFractions);
    }

    /** Re-solve payment for keep-term recast after a prepayment. */
    public static BigDecimal recastPayment(BigDecimal opening, List<BigDecimal> periodRates,
                                           List<BigDecimal> yearFractions) {
        int count = periodRates.size();
        if (count == 0) {
            return Money.money(BigDecimal.ZERO);
        }
        BigDecimal discountFactor = BigDecimal.ONE;
        for (int index = 0; index < count; index++) {
            BigDecimal accrualFactor = periodRates.get(index)
                    .multiply(yearFractions.get(index))
                    .setScale(Money.FRACTION_SCALE, Money.ROUNDING);
            discountFactor = discountFactor.multiply(BigDecimal.ONE.add(accrualFactor),
                    Money.MATH_CONTEXT);
        }
        BigDecimal annuityFactor = BigDecimal.ZERO;
        BigDecimal cumulative = BigDecimal.ONE;
        for (int index = 0; index < count; index++) {
            BigDecimal accrualFactor = periodRates.get(index)
                    .multiply(yearFractions.get(index))
                    .setScale(Money.FRACTION_SCALE, Money.ROUNDING);
            cumulative = cumulative.multiply(BigDecimal.ONE.add(accrualFactor),
                    Money.MATH_CONTEXT);
            annuityFactor = annuityFactor.add(BigDecimal.ONE.divide(cumulative,
                    Money.FRACTION_SCALE, Money.ROUNDING));
        }
        BigDecimal payment = opening.divide(annuityFactor, Money.MONEY_SCALE, Money.ROUNDING);
        return payment;
    }

    public static List<CashFlowRow> generate(Contract contract, List<Period> periods,
                                             List<BigDecimal> rates,
                                             List<BigDecimal> fractions) {
        List<CashFlowRow> rows = new ArrayList<>(periods.size());
        Map<Integer, Prepayment> prepaymentsByPeriod = new java.util.HashMap<>();
        for (Prepayment prepayment : contract.prepayments()) {
            prepaymentsByPeriod.put(prepayment.periodOrdinal(), prepayment);
        }

        BigDecimal opening = Money.money(contract.nominal());
        BigDecimal basePayment = baseScheduledPayment(contract, opening, periods, rates, fractions);
        BigDecimal scheduledPayment = basePayment;
        java.time.LocalDate previousResetDate = null;
        int shortenTermPayoffOrdinal = -1;

        for (int index = 0; index < periods.size(); index++) {
            Period period = periods.get(index);
            BigDecimal rate = rates.get(index);
            BigDecimal fraction = fractions.get(index);
            boolean lastPeriod = index == periods.size() - 1;
            boolean loanOpen = opening.signum() > 0;

            Prepayment prepayment = prepaymentsByPeriod.get(period.ordinal());
            java.time.LocalDate resetDate = contract.rateSpec().floating()
                    ? RateResolver.governingResetDate(contract, period)
                    : null;
            boolean resetBoundary = contract.rateSpec().floating()
                    && !java.util.Objects.equals(resetDate, previousResetDate);
            if (prepayment != null && loanOpen && prepayment.mode() == RecastMode.KEEP_TERM) {
                List<BigDecimal> remainingRates = rates.subList(index, rates.size());
                List<BigDecimal> remainingFractions = fractions.subList(index, fractions.size());
                scheduledPayment = recastPayment(opening, remainingRates, remainingFractions);
            } else if (contract.amortizationMethod() == loancashflow.model.AmortizationMethod.ANNUITY
                    && resetBoundary && index > 0 && loanOpen) {
                List<BigDecimal> remainingRates = rates.subList(index, rates.size());
                List<BigDecimal> remainingFractions = fractions.subList(index, fractions.size());
                scheduledPayment = recastPayment(opening, remainingRates, remainingFractions);
            }
            previousResetDate = resetDate;

            BigDecimal interest;
            BigDecimal scheduledPrincipal;
            BigDecimal prepaymentAmount = BigDecimal.ZERO.setScale(Money.MONEY_SCALE);
            if (!loanOpen) {
                interest = BigDecimal.ZERO.setScale(Money.MONEY_SCALE);
                scheduledPrincipal = BigDecimal.ZERO.setScale(Money.MONEY_SCALE);
            } else {
                interest = Money.interest(opening, rate, fraction);
                scheduledPrincipal = scheduledPrincipal(
                        contract, opening, scheduledPayment, interest, lastPeriod);
            }

            if (prepayment != null && loanOpen) {
                prepaymentAmount = Money.money(prepayment.amount());
                BigDecimal cap = opening.subtract(scheduledPrincipal);
                if (prepaymentAmount.compareTo(cap) > 0) {
                    if (prepaymentAmount.compareTo(opening) > 0
                            || prepayment.amount().compareTo(opening) > 0) {
                        throw new LoanException(ErrorCode.INVALID_PREPAYMENT,
                                "prepayment " + prepayment.amount().toPlainString()
                                        + " at period " + period.ordinal()
                                        + " exceeds outstanding principal "
                                        + opening.toPlainString());
                    }
                    prepaymentAmount = cap;
                }
            }

            BigDecimal totalPrincipal = scheduledPrincipal.add(prepaymentAmount);
            BigDecimal closing = opening.subtract(totalPrincipal);
            if (closing.signum() < 0) {
                throw new LoanException(ErrorCode.NEGATIVE_BALANCE,
                        "negative closing balance at period " + period.ordinal());
            }
            BigDecimal payment = interest.add(totalPrincipal);

            rows.add(new CashFlowRow(
                    period.ordinal(),
                    period.accrualStart(),
                    period.accrualEnd(),
                    period.paymentDate(),
                    opening,
                    BigDecimal.ZERO.setScale(Money.MONEY_SCALE),
                    rate,
                    fraction,
                    interest,
                    scheduledPrincipal,
                    prepaymentAmount,
                    totalPrincipal,
                    payment,
                    closing,
                    resetDate == null ? "" : resetDate.toString()));

            opening = closing;
            if (closing.signum() == 0 && prepayment != null
                    && prepayment.mode() == RecastMode.SHORTEN_TERM) {
                shortenTermPayoffOrdinal = period.ordinal();
            }
        }

        if (shortenTermPayoffOrdinal > 0) {
            trimAfter(rows, shortenTermPayoffOrdinal);
        }
        absorbTail(rows, contract);
        verifyPrincipalReturned(rows, contract);
        return rows;
    }

    /** Shorten-term: drop every period strictly after the payoff period. */
    private static void trimAfter(List<CashFlowRow> rows, int ordinal) {
        rows.removeIf(row -> row.ordinal() > ordinal);
    }

    private static BigDecimal baseScheduledPayment(Contract contract, BigDecimal nominal,
                                                    List<Period> periods,
                                                    List<BigDecimal> rates,
                                                    List<BigDecimal> fractions) {
        return switch (contract.amortizationMethod()) {
            case BULLET -> Money.money(BigDecimal.ZERO);
            case EQUAL_PRINCIPAL -> Money.money(nominal)
                    .divide(BigDecimal.valueOf(periodCountGuard(contract, rates.size())),
                            Money.MONEY_SCALE, Money.ROUNDING);
            case ANNUITY -> {
                if (!contract.rateSpec().floating()) {
                    yield recastPayment(nominal, rates, fractions);
                }
                int firstResetSpan = firstResetSpan(contract, periods);
                yield recastPayment(nominal,
                        rates.subList(0, firstResetSpan),
                        fractions.subList(0, firstResetSpan));
            }
        };
    }

    /** Number of periods governed by the first reset date. */
    private static int firstResetSpan(Contract contract, List<Period> periods) {
        java.time.LocalDate firstReset = contract.rateSpec().reset().firstResetDate();
        int span = 0;
        for (Period period : periods) {
            if (RateResolver.governingResetDate(contract, period).equals(firstReset)) {
                span++;
            } else {
                break;
            }
        }
        return Math.max(span, 1);
    }

    private static int periodCountGuard(Contract contract, int count) {
        if (count <= 0) {
            throw new LoanException(ErrorCode.INVALID_TERM, "contract has no accrual periods");
        }
        return count;
    }

    private static BigDecimal scheduledPrincipal(Contract contract, BigDecimal opening,
                                                  BigDecimal scheduledPayment,
                                                  BigDecimal interest, boolean lastPeriod) {
        return switch (contract.amortizationMethod()) {
            case BULLET -> lastPeriod ? opening : BigDecimal.ZERO.setScale(Money.MONEY_SCALE);
            case EQUAL_PRINCIPAL -> lastPeriod ? opening
                    : scheduledPayment.min(opening);
            case ANNUITY -> {
                if (lastPeriod) {
                    yield opening;
                }
                BigDecimal candidate = scheduledPayment.subtract(interest);
                if (candidate.signum() < 0) {
                    yield BigDecimal.ZERO.setScale(Money.MONEY_SCALE);
                }
                yield candidate.min(opening);
            }
        };
    }

    /**
     * Rounding residuals land in the last live period so that total principal equals the
     * nominal to the cent. The adjustment is applied to scheduled principal, never creating
     * a negative balance.
     */
    private static void absorbTail(List<CashFlowRow> rows, Contract contract) {
        if (rows.isEmpty()) {
            return;
        }
        BigDecimal nominal = Money.money(contract.nominal());
        BigDecimal repaid = BigDecimal.ZERO.setScale(Money.MONEY_SCALE);
        for (CashFlowRow row : rows) {
            repaid = repaid.add(row.totalPrincipal());
        }
        BigDecimal residual = nominal.subtract(repaid);
        if (residual.signum() == 0) {
            return;
        }
        int lastIndex = rows.size() - 1;
        CashFlowRow tail = rows.get(lastIndex);
        BigDecimal correctedScheduled = tail.scheduledPrincipal().add(residual);
        if (correctedScheduled.signum() < 0) {
            throw new LoanException(ErrorCode.RESIDUAL_NOT_ABSORBED,
                    "tail adjustment would create negative principal: " + residual.toPlainString());
        }
        BigDecimal correctedTotal = tail.totalPrincipal().add(residual);
        BigDecimal correctedClosing = tail.openingBalance().subtract(correctedTotal);
        if (correctedClosing.signum() < 0) {
            throw new LoanException(ErrorCode.NEGATIVE_BALANCE,
                    "tail adjustment would create negative closing balance");
        }
        rows.set(lastIndex, new CashFlowRow(
                tail.ordinal(), tail.accrualStart(), tail.accrualEnd(), tail.paymentDate(),
                tail.openingBalance(), tail.advance(), tail.interestRate(), tail.yearFraction(),
                tail.interest(),
                correctedScheduled,
                tail.prepayment(),
                correctedTotal,
                tail.interest().add(correctedTotal),
                correctedClosing,
                tail.resetDate()));
    }

    private static void verifyPrincipalReturned(List<CashFlowRow> rows, Contract contract) {
        BigDecimal nominal = Money.money(contract.nominal());
        BigDecimal repaid = BigDecimal.ZERO.setScale(Money.MONEY_SCALE);
        for (CashFlowRow row : rows) {
            repaid = repaid.add(row.totalPrincipal());
        }
        if (repaid.compareTo(nominal) != 0) {
            throw new LoanException(ErrorCode.RESIDUAL_NOT_ABSORBED,
                    "repaid principal " + repaid.toPlainString()
                            + " does not equal nominal " + nominal.toPlainString());
        }
        CashFlowRow last = rows.get(rows.size() - 1);
        if (last.closingBalance().signum() != 0) {
            throw new LoanException(ErrorCode.RESIDUAL_NOT_ABSORBED,
                    "final closing balance must be zero: " + last.closingBalance().toPlainString());
        }
    }
}
