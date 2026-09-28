package loancashflow.tests;

import loancashflow.engine.ErrorCode;
import loancashflow.engine.ScheduleEngine;
import loancashflow.model.AmortizationMethod;
import loancashflow.model.BusinessDayConvention;
import loancashflow.model.Contract;
import loancashflow.model.DayCount;
import loancashflow.model.FixingObservation;
import loancashflow.model.FixingTable;
import loancashflow.model.Frequency;
import loancashflow.model.HolidayCalendar;
import loancashflow.model.Prepayment;
import loancashflow.model.RateSpec;
import loancashflow.model.RecastMode;
import loancashflow.model.ResetSpec;
import loancashflow.model.StubConvention;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Error classification tests: bad dates, missing fixings, negative principal, overflow. */
public final class ValidationTests {

    private final TestFramework framework;

    public ValidationTests(TestFramework framework) {
        this.framework = framework;
    }

    public static void main(String[] args) {
        TestFramework framework = new TestFramework();
        new ValidationTests(framework).run();
        framework.report();
    }

    public void run() {
        negativeNominal();
        subCentNominal();
        valueDateAfterFirstPayment();
        missingFixedRate();
        missingFloatingFixing();
        floorAboveCap();
        prepaymentBeyondTerm();
        nonRegularGridRejected();
        duplicatePrepaymentPosition();
        subCentPrepayment();
        prepaymentExceedsOpening();
    }

    private Contract contract(BigDecimal nominal, LocalDate valueDate,
                              LocalDate firstPaymentDate, LocalDate maturityDate,
                              RateSpec rateSpec, FixingTable fixingTable,
                              List<Prepayment> prepayments) {
        return Contract.create(
                "validation", nominal, valueDate, firstPaymentDate, maturityDate,
                Frequency.QUARTERLY, DayCount.ACT_365F,
                AmortizationMethod.EQUAL_PRINCIPAL,
                false, StubConvention.NONE, BusinessDayConvention.NONE,
                HolidayCalendar.noHolidays(), rateSpec, fixingTable, prepayments);
    }

    private void negativeNominal() {
        framework.failsWith("negative nominal", ErrorCode.NEGATIVE_PRINCIPAL, () ->
                contract(new BigDecimal("-100.00"),
                        LocalDate.of(2024, 1, 1), LocalDate.of(2024, 4, 1),
                        LocalDate.of(2024, 10, 1),
                        RateSpec.fixed(new BigDecimal("0.05")), null, List.of()));
        framework.failsWith("zero nominal", ErrorCode.NEGATIVE_PRINCIPAL, () ->
                contract(BigDecimal.ZERO,
                        LocalDate.of(2024, 1, 1), LocalDate.of(2024, 4, 1),
                        LocalDate.of(2024, 10, 1),
                        RateSpec.fixed(new BigDecimal("0.05")), null, List.of()));
    }

    private void subCentNominal() {
        framework.failsWith("sub-cent nominal", ErrorCode.SCALE_OVERFLOW, () ->
                contract(new BigDecimal("100.005"),
                        LocalDate.of(2024, 1, 1), LocalDate.of(2024, 4, 1),
                        LocalDate.of(2024, 10, 1),
                        RateSpec.fixed(new BigDecimal("0.05")), null, List.of()));
    }

    private void valueDateAfterFirstPayment() {
        framework.failsWith("value after first pay", ErrorCode.INVALID_DATE, () ->
                contract(new BigDecimal("100.00"),
                        LocalDate.of(2024, 5, 1), LocalDate.of(2024, 4, 1),
                        LocalDate.of(2024, 10, 1),
                        RateSpec.fixed(new BigDecimal("0.05")), null, List.of()));
    }

    private void missingFixedRate() {
        framework.failsWith("null fixed rate", ErrorCode.INVALID_RATE, () ->
                Contract.create("x", new BigDecimal("100.00"),
                        LocalDate.of(2024, 1, 1), LocalDate.of(2024, 4, 1),
                        LocalDate.of(2024, 10, 1),
                        Frequency.QUARTERLY, DayCount.ACT_365F,
                        AmortizationMethod.EQUAL_PRINCIPAL,
                        false, StubConvention.NONE, BusinessDayConvention.NONE,
                        HolidayCalendar.noHolidays(),
                        new RateSpec(false, null, null, null, null, null),
                        null, List.of()));
    }

    private void missingFloatingFixing() {
        RateSpec floating = RateSpec.floating(BigDecimal.ZERO, null, null,
                new ResetSpec(LocalDate.of(2024, 1, 1), Frequency.QUARTERLY));
        Contract noFixing = contract(new BigDecimal("10000.00"),
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 4, 1),
                LocalDate.of(2024, 7, 1), floating, FixingTable.of(), List.of());
        framework.failsWith("missing fixing", ErrorCode.MISSING_FIXING,
                () -> new ScheduleEngine().generate(noFixing));
    }

    private void floorAboveCap() {
        framework.failsWith("floor > cap", ErrorCode.INVALID_RATE, () ->
                contract(new BigDecimal("10000.00"),
                        LocalDate.of(2024, 1, 1), LocalDate.of(2024, 4, 1),
                        LocalDate.of(2024, 7, 1),
                        RateSpec.floating(BigDecimal.ZERO,
                                new BigDecimal("0.09"), new BigDecimal("0.05"),
                                new ResetSpec(LocalDate.of(2024, 1, 1),
                                        Frequency.QUARTERLY)),
                        FixingTable.of(), List.of()));
    }

    private void prepaymentBeyondTerm() {
        Contract contract = contract(new BigDecimal("10000.00"),
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 4, 1),
                LocalDate.of(2024, 7, 1),
                RateSpec.fixed(new BigDecimal("0.05")), null,
                List.of(new Prepayment(9, new BigDecimal("100.00"),
                        RecastMode.SHORTEN_TERM)));
        framework.failsWith("prepayment beyond term", ErrorCode.INVALID_PREPAYMENT,
                () -> new ScheduleEngine().generate(contract));
    }

    private void nonRegularGridRejected() {
        Contract contract = contract(new BigDecimal("10000.00"),
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 4, 1),
                LocalDate.of(2024, 12, 1),
                RateSpec.fixed(new BigDecimal("0.05")), null, List.of());
        framework.failsWith("non-regular grid NONE", ErrorCode.INVALID_TERM,
                () -> new ScheduleEngine().generate(contract));
    }

    private void duplicatePrepaymentPosition() {
        framework.failsWith("duplicate prepayment position", ErrorCode.INVALID_PREPAYMENT,
                () -> contract(new BigDecimal("10000.00"),
                        LocalDate.of(2024, 1, 1), LocalDate.of(2024, 4, 1),
                        LocalDate.of(2024, 10, 1),
                        RateSpec.fixed(new BigDecimal("0.05")), null,
                        List.of(
                                new Prepayment(2, new BigDecimal("100.00"),
                                        RecastMode.SHORTEN_TERM),
                                new Prepayment(2, new BigDecimal("200.00"),
                                        RecastMode.KEEP_TERM))));
    }

    private void subCentPrepayment() {
        framework.failsWith("sub-cent prepayment", ErrorCode.SCALE_OVERFLOW,
                () -> contract(new BigDecimal("10000.00"),
                        LocalDate.of(2024, 1, 1), LocalDate.of(2024, 4, 1),
                        LocalDate.of(2024, 10, 1),
                        RateSpec.fixed(new BigDecimal("0.05")), null,
                        List.of(new Prepayment(1, new BigDecimal("100.001"),
                                RecastMode.SHORTEN_TERM))));
    }

    private void prepaymentExceedsOpening() {
        // Equal-principal quarterly: after one period opening is below nominal; asking for
        // the full nominal at period 2 exceeds the opening balance and is rejected.
        Contract contract = contract(new BigDecimal("12000.00"),
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 4, 1),
                LocalDate.of(2025, 1, 1),
                RateSpec.fixed(new BigDecimal("0.05")), null,
                List.of(new Prepayment(2, new BigDecimal("12000.00"),
                        RecastMode.KEEP_TERM)));
        framework.failsWith("prepayment exceeds opening", ErrorCode.INVALID_PREPAYMENT,
                () -> new ScheduleEngine().generate(contract));
    }
}
