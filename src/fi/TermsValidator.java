package fi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class TermsValidator {

    private TermsValidator() {
    }

    public static void validate(BondTerms terms) {
        if (terms == null) {
            throw new ScheduleException(ErrorCode.INVALID_TERMS, "terms are null");
        }
        require(terms.valueDate() != null, "valueDate is null");
        require(terms.maturityDate() != null, "maturityDate is null");
        require(terms.frequency() != null, "frequency is null");
        require(terms.stubType() != null, "stubType is null");
        require(terms.dayCount() != null, "dayCount is null");
        require(terms.convention() != null, "convention is null");
        require(terms.calendar() != null, "calendar is null");
        require(terms.rateSteps() != null, "rateSteps is null");
        require(terms.notionalSteps() != null, "notionalSteps is null");
        require(!terms.rateSteps().isEmpty(), "rateSteps must not be empty");
        require(!terms.notionalSteps().isEmpty(), "notionalSteps must not be empty");

        LocalDate value = terms.valueDate();
        LocalDate maturity = terms.maturityDate();
        if (!maturity.isAfter(value)) {
            throw new ScheduleException(ErrorCode.DATE_ORDER,
                    "maturity " + maturity + " must be after valueDate " + value);
        }

        if (terms.exDividendBusinessDays() < 0) {
            throw new ScheduleException(ErrorCode.CONFLICTING_TERMS,
                    "exDividendBusinessDays must be zero or positive");
        }

        validateSteps(terms);

        if (terms.stubType() == StubType.NONE && !isRegular(value, maturity, terms)) {
            throw new ScheduleException(ErrorCode.CONFLICTING_TERMS,
                    "stubType NONE but term is not a whole number of periods");
        }
    }

    private static void validateSteps(BondTerms terms) {
        LocalDate value = terms.valueDate();
        LocalDate maturity = terms.maturityDate();

        LocalDate previousRate = null;
        for (RateStep step : terms.rateSteps()) {
            if (step.startDate() == null || step.annualRate() == null) {
                throw new ScheduleException(ErrorCode.INVALID_TERMS,
                        "rate step has null fields");
            }
            if (step.annualRate().signum() < 0) {
                throw new ScheduleException(ErrorCode.CONFLICTING_TERMS,
                        "negative coupon rate: " + step.annualRate());
            }
            if (previousRate != null && !step.startDate().isAfter(previousRate)) {
                throw new ScheduleException(ErrorCode.DATE_ORDER,
                        "rate step dates must be strictly ascending");
            }
            previousRate = step.startDate();
        }
        if (!terms.rateSteps().get(0).startDate().equals(value)) {
            throw new ScheduleException(ErrorCode.CONFLICTING_TERMS,
                    "first rate step must start on valueDate");
        }
        if (terms.rateSteps().get(terms.rateSteps().size() - 1).startDate().isAfter(maturity)) {
            throw new ScheduleException(ErrorCode.DATE_ORDER,
                    "rate step starts after maturity");
        }

        LocalDate previousNotional = null;
        BigDecimal lastNotional = null;
        for (NotionalStep step : terms.notionalSteps()) {
            if (step.startDate() == null || step.outstandingNotional() == null) {
                throw new ScheduleException(ErrorCode.INVALID_TERMS,
                        "notional step has null fields");
            }
            if (step.outstandingNotional().signum() < 0) {
                throw new ScheduleException(ErrorCode.NEGATIVE_NOTIONAL,
                        "negative notional: " + step.outstandingNotional());
            }
            if (previousNotional != null) {
                if (!step.startDate().isAfter(previousNotional)) {
                    throw new ScheduleException(ErrorCode.DATE_ORDER,
                            "notional step dates must be strictly ascending");
                }
                if (step.outstandingNotional().compareTo(lastNotional) > 0) {
                    throw new ScheduleException(ErrorCode.CONFLICTING_TERMS,
                            "outstanding notional must not rise after amortization at "
                                    + step.startDate());
                }
            }
            previousNotional = step.startDate();
            lastNotional = step.outstandingNotional();
        }
        if (!terms.notionalSteps().get(0).startDate().equals(value)) {
            throw new ScheduleException(ErrorCode.CONFLICTING_TERMS,
                    "first notional step must start on valueDate");
        }
        LocalDate lastNotionalDate =
                terms.notionalSteps().get(terms.notionalSteps().size() - 1).startDate();
        if (lastNotionalDate.isAfter(maturity)) {
            throw new ScheduleException(ErrorCode.DATE_ORDER,
                    "notional step starts after maturity");
        }
    }

    static boolean isRegular(LocalDate value, LocalDate maturity, BondTerms terms) {
        List<LocalDate> backwards = UnadjustedSchedule.backwardDates(terms);
        LocalDate first = backwards.get(0);
        if (!first.equals(value)) {
            LocalDate oneMore = UnadjustedSchedule.minusMonths(
                    first, terms.frequency().months(), terms.eom());
            return oneMore.equals(value);
        }
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ScheduleException(ErrorCode.INVALID_TERMS, message);
        }
    }
}
