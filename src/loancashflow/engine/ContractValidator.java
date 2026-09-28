package loancashflow.engine;

import loancashflow.model.Contract;
import loancashflow.model.Prepayment;
import loancashflow.model.RateSpec;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/** Static contract validation: dates, principal scale/sign, rate and reset consistency. */
public final class ContractValidator {

    private ContractValidator() {
    }

    public static void validate(Contract contract) {
        if (contract == null) {
            throw new LoanException(ErrorCode.INVALID_DATE, "contract is null");
        }
        validateDates(contract);
        validateNominal(contract);
        validateRate(contract);
        validatePrepayments(contract);
    }

    private static void validateDates(Contract contract) {
        LocalDate valueDate = contract.valueDate();
        LocalDate firstPaymentDate = contract.firstPaymentDate();
        LocalDate maturityDate = contract.maturityDate();
        if (valueDate == null || firstPaymentDate == null || maturityDate == null) {
            throw new LoanException(ErrorCode.INVALID_DATE,
                    "valueDate, firstPaymentDate and maturityDate must all be present");
        }
        if (!valueDate.isBefore(firstPaymentDate)) {
            throw new LoanException(ErrorCode.INVALID_DATE,
                    "valueDate " + valueDate + " must precede firstPaymentDate " + firstPaymentDate);
        }
        if (firstPaymentDate.isAfter(maturityDate)) {
            throw new LoanException(ErrorCode.INVALID_DATE,
                    "firstPaymentDate " + firstPaymentDate
                            + " must not follow maturityDate " + maturityDate);
        }
        if (contract.frequency() == null || contract.dayCount() == null
                || contract.businessDayConvention() == null
                || contract.stubConvention() == null || contract.calendar() == null) {
            throw new LoanException(ErrorCode.INVALID_DATE,
                    "frequency, dayCount, conventions and calendar must all be present");
        }
    }

    private static void validateNominal(Contract contract) {
        BigDecimal nominal = contract.nominal();
        if (nominal == null) {
            throw new LoanException(ErrorCode.NEGATIVE_PRINCIPAL, "nominal is missing");
        }
        if (nominal.signum() <= 0) {
            throw new LoanException(ErrorCode.NEGATIVE_PRINCIPAL,
                    "nominal must be strictly positive: " + nominal.toPlainString());
        }
        Money.requireMoneyScale("nominal", nominal);
    }

    private static void validateRate(Contract contract) {
        RateSpec spec = contract.rateSpec();
        if (spec == null) {
            throw new LoanException(ErrorCode.INVALID_RATE, "rateSpec is missing");
        }
        if (!spec.floating()) {
            if (spec.fixedRate() == null) {
                throw new LoanException(ErrorCode.INVALID_RATE, "fixed contract needs a fixedRate");
            }
            if (spec.fixedRate().signum() < 0) {
                throw new LoanException(ErrorCode.INVALID_RATE,
                        "fixedRate must not be negative: " + spec.fixedRate().toPlainString());
            }
            return;
        }
        if (spec.reset() == null || spec.reset().firstResetDate() == null
                || spec.reset().frequency() == null) {
            throw new LoanException(ErrorCode.INVALID_RATE,
                    "floating contract needs a reset spec with firstResetDate and frequency");
        }
        if (spec.spread() == null) {
            throw new LoanException(ErrorCode.INVALID_RATE, "floating contract needs a spread");
        }
        if (spec.floor() != null && spec.cap() != null
                && spec.floor().compareTo(spec.cap()) > 0) {
            throw new LoanException(ErrorCode.INVALID_RATE, "floor must not exceed cap");
        }
        if (contract.fixingTable() == null) {
            throw new LoanException(ErrorCode.INVALID_RATE,
                    "floating contract needs a (possibly empty) fixing table");
        }
    }

    private static void validatePrepayments(Contract contract) {
        Set<Integer> positions = new HashSet<>();
        for (Prepayment prepayment : contract.prepayments()) {
            if (prepayment.periodOrdinal() < 1) {
                throw new LoanException(ErrorCode.INVALID_PREPAYMENT,
                        "prepayment periodOrdinal must be 1-based and positive: "
                                + prepayment.periodOrdinal());
            }
            if (!positions.add(prepayment.periodOrdinal())) {
                throw new LoanException(ErrorCode.INVALID_PREPAYMENT,
                        "duplicate prepayment position " + prepayment.periodOrdinal());
            }
            if (prepayment.amount() == null || prepayment.amount().signum() <= 0) {
                throw new LoanException(ErrorCode.INVALID_PREPAYMENT,
                        "prepayment amount must be strictly positive: " + prepayment.amount());
            }
            Money.requireMoneyScale("prepayment amount", prepayment.amount());
            if (prepayment.mode() == null) {
                throw new LoanException(ErrorCode.INVALID_PREPAYMENT,
                        "prepayment recast mode is missing");
            }
        }
    }
}
