package loancashflow.engine;

import loancashflow.model.Contract;
import loancashflow.model.CashFlowRow;
import loancashflow.model.FixingObservation;
import loancashflow.model.FixingTable;
import loancashflow.model.ScheduleResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Facade: full generation and fixing-correction re-generation. */
public final class ScheduleEngine {

    public ScheduleResult generate(Contract contract) {
        ContractValidator.validate(contract);
        List<Period> periods = PeriodSchedule.generate(contract);
        validatePrepaymentPositions(contract, periods);
        List<BigDecimal> rates = new ArrayList<>(periods.size());
        List<BigDecimal> fractions = new ArrayList<>(periods.size());
        for (Period period : periods) {
            rates.add(RateResolver.resolve(contract, period));
            fractions.add(contract.dayCount().yearFraction(
                    period.accrualStart(), period.accrualEnd()));
        }
        List<CashFlowRow> rows = AmortizationCalculator.generate(
                contract, periods, rates, fractions);
        return new ScheduleResult(contract, rows);
    }

    /**
     * Apply a correcting fixing observation and regenerate. Results are identical to
     * building a fresh contract/table containing the correction and generating fully.
     */
    public ScheduleResult applyFixingCorrection(Contract contract, FixingObservation correction) {
        List<FixingObservation> all = new ArrayList<>();
        contract.fixingTable().byResetDate().values().forEach(all::addAll);
        all.add(correction);
        Contract corrected = new Contract(
                contract.id(), contract.nominal(), contract.valueDate(),
                contract.firstPaymentDate(), contract.maturityDate(), contract.frequency(),
                contract.dayCount(), contract.amortizationMethod(), contract.endOfMonthRule(),
                contract.stubConvention(), contract.businessDayConvention(), contract.calendar(),
                contract.rateSpec(), FixingTable.ofList(all), contract.prepayments());
        return generate(corrected);
    }

    /**
     * Recompute from the first period affected by the corrected fixing; the returned rows
     * before that ordinal are reused. Must match full generation period by period.
     */
    public ScheduleResult regenerateFromFirstAffected(Contract contract,
                                                      List<FixingObservation> correctedFixings) {
        ScheduleResult baseline = generate(contract);
        FixingTable correctedTable = FixingTable.ofList(correctedFixings);
        LocalDate earliestReset = null;
        for (FixingObservation observation : correctedFixings) {
            BigDecimal before = contract.fixingTable().latest(observation.resetDate());
            BigDecimal after = correctedTable.latest(observation.resetDate());
            boolean changed = before == null
                    ? after != null
                    : after == null || before.compareTo(after) != 0;
            if (changed && (earliestReset == null
                    || observation.resetDate().isBefore(earliestReset))) {
                earliestReset = observation.resetDate();
            }
        }
        if (earliestReset == null) {
            return baseline;
        }

        Contract correctedContract = new Contract(
                contract.id(), contract.nominal(), contract.valueDate(),
                contract.firstPaymentDate(), contract.maturityDate(), contract.frequency(),
                contract.dayCount(), contract.amortizationMethod(), contract.endOfMonthRule(),
                contract.stubConvention(), contract.businessDayConvention(), contract.calendar(),
                contract.rateSpec(), correctedTable, contract.prepayments());

        List<Period> periods = PeriodSchedule.generate(correctedContract);
        int firstAffectedOrdinal = Integer.MAX_VALUE;
        for (Period period : periods) {
            LocalDate governing = RateResolver.governingResetDate(correctedContract, period);
            if (!governing.isBefore(earliestReset)) {
                firstAffectedOrdinal = period.ordinal();
                break;
            }
        }
        if (firstAffectedOrdinal == Integer.MAX_VALUE) {
            return baseline;
        }

        List<BigDecimal> rates = new ArrayList<>(periods.size());
        List<BigDecimal> fractions = new ArrayList<>(periods.size());
        for (Period period : periods) {
            rates.add(RateResolver.resolve(correctedContract, period));
            fractions.add(correctedContract.dayCount().yearFraction(
                    period.accrualStart(), period.accrualEnd()));
        }
        List<CashFlowRow> full = AmortizationCalculator.generate(
                correctedContract, periods, rates, fractions);
        List<CashFlowRow> merged = new ArrayList<>(full.size());
        for (CashFlowRow row : full) {
            merged.add(row.ordinal() < firstAffectedOrdinal
                    ? baseline.rows().get(row.ordinal() - 1)
                    : row);
        }
        return new ScheduleResult(correctedContract, merged);
    }

    private static void validatePrepaymentPositions(Contract contract, List<Period> periods) {
        int lastOrdinal = periods.get(periods.size() - 1).ordinal();
        for (var prepayment : contract.prepayments()) {
            if (prepayment.periodOrdinal() > lastOrdinal) {
                throw new LoanException(ErrorCode.INVALID_PREPAYMENT,
                        "prepayment position " + prepayment.periodOrdinal()
                                + " is beyond the final period ordinal " + lastOrdinal);
            }
        }
    }
}
