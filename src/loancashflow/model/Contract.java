package loancashflow.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A loan contract: principal, dates, accrual and repayment rules, optional floating-rate
 * machinery, holiday calendar and frozen prepayment instructions.
 */
public record Contract(
        String id,
        BigDecimal nominal,
        LocalDate valueDate,
        LocalDate firstPaymentDate,
        LocalDate maturityDate,
        Frequency frequency,
        DayCount dayCount,
        AmortizationMethod amortizationMethod,
        boolean endOfMonthRule,
        StubConvention stubConvention,
        BusinessDayConvention businessDayConvention,
        HolidayCalendar calendar,
        RateSpec rateSpec,
        FixingTable fixingTable,
        List<Prepayment> prepayments) {

    public Contract {
        prepayments = prepayments == null ? List.of() : List.copyOf(prepayments);
    }

    /** Convenience builder; validates the contract. */
    public static Contract create(
            String id,
            BigDecimal nominal,
            LocalDate valueDate,
            LocalDate firstPaymentDate,
        LocalDate maturityDate,
        Frequency frequency,
        DayCount dayCount,
        AmortizationMethod amortizationMethod,
        boolean endOfMonthRule,
        StubConvention stubConvention,
            BusinessDayConvention businessDayConvention,
            HolidayCalendar calendar,
            RateSpec rateSpec,
            FixingTable fixingTable,
            List<Prepayment> prepayments) {
        Contract contract = new Contract(id, nominal, valueDate, firstPaymentDate, maturityDate,
                frequency, dayCount, amortizationMethod, endOfMonthRule, stubConvention, businessDayConvention,
                calendar, rateSpec, fixingTable, prepayments);
        loancashflow.engine.ContractValidator.validate(contract);
        return contract;
    }
}
