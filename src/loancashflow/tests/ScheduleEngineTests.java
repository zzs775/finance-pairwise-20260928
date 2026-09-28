package loancashflow.tests;

import loancashflow.engine.LoanException;
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
import loancashflow.model.ScheduleResult;
import loancashflow.model.StubConvention;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Tests covering the three amortization methods, stubs, leap days and business days. */
public final class ScheduleEngineTests {

    private final TestFramework framework;

    public ScheduleEngineTests(TestFramework framework) {
        this.framework = framework;
    }

    public static void main(String[] args) {
        TestFramework framework = new TestFramework();
        new ScheduleEngineTests(framework).run();
        framework.report();
    }

    public void run() {
        shortFirstStub();
        longFirstStub();
        leapDayAct365();
        businessDayAdjustmentKeepsAccrualBoundaries();
        modifiedFollowingRollsBack();
        endOfMonthRule();
        floatingFloorAndCap();
        prepaymentShortenTerm();
        prepaymentKeepTerm();
        noNegativeBalanceOnFullPrepayment();
    }

    private Contract baseContract(
            LocalDate valueDate, LocalDate firstPaymentDate, LocalDate maturityDate,
            StubConvention stub, BusinessDayConvention convention, HolidayCalendar calendar,
            RateSpec rateSpec, FixingTable fixingTable, List<Prepayment> prepayments) {
        return baseContract(valueDate, firstPaymentDate, maturityDate, stub, convention,
                calendar, rateSpec, fixingTable, prepayments, false);
    }

    private Contract baseContract(
            LocalDate valueDate, LocalDate firstPaymentDate, LocalDate maturityDate,
            StubConvention stub, BusinessDayConvention convention, HolidayCalendar calendar,
            RateSpec rateSpec, FixingTable fixingTable, List<Prepayment> prepayments,
            boolean endOfMonthRule) {
        return Contract.create(
                "test", new BigDecimal("12000.00"),
                valueDate, firstPaymentDate, maturityDate,
                Frequency.QUARTERLY, DayCount.ACT_365F,
                AmortizationMethod.EQUAL_PRINCIPAL,
                endOfMonthRule, stub, convention, calendar,
                rateSpec, fixingTable, prepayments);
    }

    private void shortFirstStub() {
        Contract contract = baseContract(
                LocalDate.of(2024, 2, 20), LocalDate.of(2024, 3, 31),
                LocalDate.of(2024, 12, 31),
                StubConvention.SHORT_FIRST, BusinessDayConvention.NONE,
                HolidayCalendar.noHolidays(), RateSpec.fixed(new BigDecimal("0.05")),
                null, List.of());
        ScheduleResult result = new ScheduleEngine().generate(contract);
        framework.eq("short-first periods", 4, result.rows().size());
        framework.eq("short-first p1 start", LocalDate.of(2024, 2, 20),
                result.rows().get(0).accrualStart());
        framework.eq("short-first p1 end", LocalDate.of(2024, 3, 31),
                result.rows().get(0).accrualEnd());
        framework.eq("short-first p2 end", LocalDate.of(2024, 6, 30),
                result.rows().get(1).accrualEnd());
        ManualOracleTests.assertIdentity(result);
    }

    private void longFirstStub() {
        Contract contract = baseContract(
                LocalDate.of(2024, 1, 31), LocalDate.of(2024, 3, 31),
                LocalDate.of(2024, 12, 31),
                StubConvention.LONG_FIRST, BusinessDayConvention.NONE,
                HolidayCalendar.noHolidays(), RateSpec.fixed(new BigDecimal("0.05")),
                null, List.of(), true);
        ScheduleResult result = new ScheduleEngine().generate(contract);
        // Grid 3/31, 6/30, 9/30, 12/31; first period 1/31->3/31 is a two-month long stub.
        framework.eq("long-first periods", 4, result.rows().size());
        framework.eq("long-first p1 start", LocalDate.of(2024, 1, 31),
                result.rows().get(0).accrualStart());
        framework.eq("long-first p1 end", LocalDate.of(2024, 3, 31),
                result.rows().get(0).accrualEnd());
        framework.eq("long-first p2 end", LocalDate.of(2024, 6, 30),
                result.rows().get(1).accrualEnd());
        ManualOracleTests.assertIdentity(result);
    }

    private void leapDayAct365() {
        Contract contract = baseContract(
                LocalDate.of(2024, 1, 31), LocalDate.of(2024, 4, 30),
                LocalDate.of(2024, 4, 30),
                StubConvention.NONE, BusinessDayConvention.NONE,
                HolidayCalendar.noHolidays(), RateSpec.fixed(new BigDecimal("0.10")),
                null, List.of());
        ScheduleResult result = new ScheduleEngine().generate(contract);
        // 2024-01-31 -> 2024-04-30 spans 90 days including 2024-02-29.
        framework.eq("leap fraction",
                new BigDecimal("0.2465753425"), result.rows().get(0).yearFraction());
        framework.eqMoney("leap interest", new BigDecimal("295.89"),
                result.rows().get(0).interest());
    }

    private void businessDayAdjustmentKeepsAccrualBoundaries() {
        HolidayCalendar calendar = HolidayCalendar.of(Set.of());
        Contract contract = baseContract(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 31),
                LocalDate.of(2024, 3, 31),
                StubConvention.NONE, BusinessDayConvention.FOLLOWING, calendar,
                RateSpec.fixed(new BigDecimal("0.05")), null, List.of());
        ScheduleResult result = new ScheduleEngine().generate(contract);
        framework.eq("payment rolled to Monday", LocalDate.of(2024, 4, 1),
                result.rows().get(0).paymentDate());
        framework.eq("accrual end untouched", LocalDate.of(2024, 3, 31),
                result.rows().get(0).accrualEnd());
    }

    private void modifiedFollowingRollsBack() {
        // 2024-03-31 is a Sunday; following lands in April, modified-following rolls back.
        Contract contract = baseContract(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 31),
                LocalDate.of(2024, 3, 31),
                StubConvention.NONE, BusinessDayConvention.MODIFIED_FOLLOWING,
                HolidayCalendar.of(Set.of()),
                RateSpec.fixed(new BigDecimal("0.05")), null, List.of());
        ScheduleResult result = new ScheduleEngine().generate(contract);
        framework.eq("mod-following Friday", LocalDate.of(2024, 3, 29),
                result.rows().get(0).paymentDate());
    }

    private void endOfMonthRule() {
        Contract contract = Contract.create(
                "eom", new BigDecimal("1000.00"),
                LocalDate.of(2024, 1, 31), LocalDate.of(2024, 3, 31),
                LocalDate.of(2024, 5, 31),
                Frequency.MONTHLY, DayCount.ACT_365F,
                AmortizationMethod.BULLET,
                true, StubConvention.NONE, BusinessDayConvention.NONE,
                HolidayCalendar.noHolidays(),
                RateSpec.fixed(new BigDecimal("0.05")), null, List.of());
        ScheduleResult result = new ScheduleEngine().generate(contract);
        framework.eq("eom p2 end", LocalDate.of(2024, 4, 30),
                result.rows().get(1).accrualEnd());
        framework.eq("eom p3 end", LocalDate.of(2024, 5, 31),
                result.rows().get(2).accrualEnd());
    }

    private Contract floatingContract(FixingTable table, List<Prepayment> prepayments,
                                      RecastMode ignoredMode) {
        return Contract.create(
                "float", new BigDecimal("10000.00"),
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 4, 1),
                LocalDate.of(2024, 10, 1),
                Frequency.QUARTERLY, DayCount.ACT_360,
                AmortizationMethod.BULLET,
                false, StubConvention.NONE, BusinessDayConvention.NONE,
                HolidayCalendar.noHolidays(),
                RateSpec.floating(new BigDecimal("0.01"),
                        new BigDecimal("0.03"), new BigDecimal("0.08"),
                        new ResetSpec(LocalDate.of(2024, 1, 1), Frequency.QUARTERLY)),
                table, prepayments);
    }

    private void floatingFloorAndCap() {
        FixingTable table = FixingTable.of(
                new FixingObservation(LocalDate.of(2024, 1, 1), new BigDecimal("0.010"), 1),
                new FixingObservation(LocalDate.of(2024, 4, 1), new BigDecimal("0.050"), 1),
                new FixingObservation(LocalDate.of(2024, 7, 1), new BigDecimal("0.100"), 1));
        ScheduleResult result = new ScheduleEngine().generate(
                floatingContract(table, List.of(), null));
        // 0.01+0.01=0.02 -> floor 0.03; 0.05+0.01=0.06; 0.10+0.01=0.11 -> cap 0.08.
        framework.eqMoney("floor", new BigDecimal("0.0300000000"),
                result.rows().get(0).interestRate());
        framework.eqMoney("uncollared", new BigDecimal("0.0600000000"),
                result.rows().get(1).interestRate());
        framework.eqMoney("cap", new BigDecimal("0.0800000000"),
                result.rows().get(2).interestRate());
        framework.eq("reset recorded", "2024-01-01", result.rows().get(0).resetDate());
    }

    private void prepaymentShortenTerm() {
        FixingTable table = FixingTable.of(
                new FixingObservation(LocalDate.of(2024, 1, 1), new BigDecimal("0.04"), 1),
                new FixingObservation(LocalDate.of(2024, 4, 1), new BigDecimal("0.04"), 1),
                new FixingObservation(LocalDate.of(2024, 7, 1), new BigDecimal("0.04"), 1));
        Prepayment prepayment = new Prepayment(2, new BigDecimal("10000.00"),
                RecastMode.SHORTEN_TERM);
        ScheduleResult result = new ScheduleEngine().generate(
                floatingContract(table, List.of(prepayment), RecastMode.SHORTEN_TERM));
        framework.eq("shorten ends at prepayment period", 2, result.rows().size());
        framework.eqMoney("prepay recorded", new BigDecimal("10000.00"),
                result.rows().get(1).prepayment());
        framework.eqMoney("final zero", BigDecimal.ZERO.setScale(2),
                result.rows().get(1).closingBalance());
        ManualOracleTests.assertIdentity(result);
    }

    private void prepaymentKeepTerm() {
        Contract contract = Contract.create(
                "keep", new BigDecimal("12000.00"),
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 4, 1),
                LocalDate.of(2025, 1, 1),
                Frequency.QUARTERLY, DayCount.THIRTY_360,
                AmortizationMethod.ANNUITY,
                false, StubConvention.NONE, BusinessDayConvention.NONE,
                HolidayCalendar.noHolidays(),
                RateSpec.fixed(new BigDecimal("0.08")), null,
                List.of(new Prepayment(2, new BigDecimal("4000.00"), RecastMode.KEEP_TERM)));
        ScheduleResult result = new ScheduleEngine().generate(contract);
        framework.eq("keep term keeps periods", 4, result.rows().size());
        framework.eqMoney("kept final zero", BigDecimal.ZERO.setScale(2),
                result.rows().get(3).closingBalance());
        // Scheduled (non-prepayment) payment after recast must drop below the original level-pay.
        BigDecimal scheduledP1 = result.rows().get(0).interest()
                .add(result.rows().get(0).scheduledPrincipal());
        BigDecimal scheduledP2 = result.rows().get(1).interest()
                .add(result.rows().get(1).scheduledPrincipal());
        framework.check("recast payment lower",
                scheduledP2.compareTo(scheduledP1) < 0);
        ManualOracleTests.assertIdentity(result);
    }

    private void noNegativeBalanceOnFullPrepayment() {
        FixingTable table = FixingTable.of(
                new FixingObservation(LocalDate.of(2024, 1, 1), new BigDecimal("0.04"), 1),
                new FixingObservation(LocalDate.of(2024, 4, 1), new BigDecimal("0.04"), 1),
                new FixingObservation(LocalDate.of(2024, 7, 1), new BigDecimal("0.04"), 1));
        // Asking more than outstanding is clamped to outstanding (still classified invalid
        // when even the raw amount exceeds opening; see ValidationTests).
        Prepayment prepayment = new Prepayment(1, new BigDecimal("9999.99"),
                RecastMode.SHORTEN_TERM);
        ScheduleResult result = new ScheduleEngine().generate(
                floatingContract(table, List.of(prepayment), RecastMode.SHORTEN_TERM));
        framework.eqMoney("no negative balance", BigDecimal.ZERO.setScale(2),
                result.rows().get(result.rows().size() - 1).closingBalance());
        ManualOracleTests.assertIdentity(result);
    }
}
