package loanengine.model;

import loanengine.dates.BusinessDayCalendar;
import loanengine.dates.DayCount;
import loanengine.dates.DayConvention;
import loanengine.dates.Frequency;
import loanengine.core.LoanException;
import loanengine.rate.ResetSpec;
import loanengine.schedule.AmortizationType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record Contract(
        BigDecimal notional,
        LocalDate valueDate,
        LocalDate maturityDate,
        AmortizationType amortizationType,
        Frequency frequency,
        DayCount dayCount,
        ResetSpec reset,
        DayConvention dayConvention,
        boolean monthEndRule,
        LocalDate firstPaymentDate,
        BusinessDayCalendar calendar,
        List<Prepayment> prepayments) {

    public static final int MONEY_SCALE = 2;

    public Contract {
        Objects.requireNonNull(notional, "notional");
        Objects.requireNonNull(valueDate, "valueDate");
        Objects.requireNonNull(maturityDate, "maturityDate");
        Objects.requireNonNull(amortizationType, "amortizationType");
        Objects.requireNonNull(frequency, "frequency");
        Objects.requireNonNull(dayCount, "dayCount");
        Objects.requireNonNull(reset, "reset");
        if (notional.signum() <= 0) {
            throw new LoanException(LoanException.Category.NEGATIVE_PRINCIPAL,
                    "notional must be positive: " + notional);
        }
        requireMoneyScale(notional, "notional");
        if (!maturityDate.isAfter(valueDate)) {
            throw new LoanException(LoanException.Category.INVALID_DATE,
                    "maturity " + maturityDate + " must be after value date " + valueDate);
        }
        if (firstPaymentDate != null
                && (!firstPaymentDate.isAfter(valueDate)
                        || !firstPaymentDate.isBefore(maturityDate))) {
            throw new LoanException(LoanException.Category.INVALID_DATE,
                    "first payment date must lie strictly between value and maturity");
        }
        if (dayConvention == null) {
            dayConvention = DayConvention.NONE;
        }
        if (calendar == null) {
            calendar = new BusinessDayCalendar(Set.of());
        }
        prepayments = prepayments == null ? List.of() : List.copyOf(prepayments);
        validatePrepayments(prepayments, valueDate, maturityDate);
        calendar = Objects.requireNonNull(calendar);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static void requireMoneyScale(BigDecimal amount, String field) {
        try {
            amount.setScale(MONEY_SCALE, java.math.RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new LoanException(LoanException.Category.SCALE_OVERFLOW,
                    field + " exceeds money scale " + MONEY_SCALE + ": " + amount);
        }
    }

    private static void validatePrepayments(List<Prepayment> prepayments,
                                            LocalDate valueDate, LocalDate maturityDate) {
        TreeSet<LocalDate> seen = new TreeSet<>();
        for (Prepayment prepayment : prepayments) {
            if (prepayment.amount().signum() <= 0) {
                throw new LoanException(LoanException.Category.NEGATIVE_PRINCIPAL,
                        "prepayment must be positive: " + prepayment.amount());
            }
            requireMoneyScale(prepayment.amount(), "prepayment");
            LocalDate date = prepayment.valueDate();
            if (!date.isAfter(valueDate) || date.isAfter(maturityDate)) {
                throw new LoanException(LoanException.Category.INVALID_DATE,
                        "prepayment date outside loan life: " + date);
            }
            if (!seen.add(date)) {
                throw new LoanException(LoanException.Category.INVALID_DATE,
                        "duplicate prepayment date: " + date);
            }
        }
    }

    public static final class Builder {
        private BigDecimal notional;
        private LocalDate valueDate;
        private LocalDate maturityDate;
        private AmortizationType amortizationType = AmortizationType.EQUAL_PAYMENT;
        private Frequency frequency = Frequency.MONTHLY;
        private DayCount dayCount = DayCount.ACT_365F;
        private ResetSpec reset;
        private DayConvention dayConvention = DayConvention.NONE;
        private boolean monthEndRule;
        private LocalDate firstPaymentDate;
        private BusinessDayCalendar calendar = new BusinessDayCalendar(Set.of());
        private List<Prepayment> prepayments = List.of();

        public Builder notional(BigDecimal v) { this.notional = v; return this; }
        public Builder valueDate(LocalDate v) { this.valueDate = v; return this; }
        public Builder maturityDate(LocalDate v) { this.maturityDate = v; return this; }
        public Builder amortizationType(AmortizationType v) { this.amortizationType = v; return this; }
        public Builder frequency(Frequency v) { this.frequency = v; return this; }
        public Builder dayCount(DayCount v) { this.dayCount = v; return this; }
        public Builder reset(ResetSpec v) { this.reset = v; return this; }
        public Builder dayConvention(DayConvention v) { this.dayConvention = v; return this; }
        public Builder monthEndRule(boolean v) { this.monthEndRule = v; return this; }
        public Builder firstPaymentDate(LocalDate v) { this.firstPaymentDate = v; return this; }
        public Builder calendar(BusinessDayCalendar v) { this.calendar = v; return this; }
        public Builder prepayments(List<Prepayment> v) { this.prepayments = v; return this; }

        public Contract build() {
            return new Contract(notional, valueDate, maturityDate, amortizationType, frequency,
                    dayCount, reset, dayConvention, monthEndRule, firstPaymentDate, calendar,
                    prepayments);
        }
    }
}
