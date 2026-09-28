package loancashflow.tests;

import loancashflow.engine.ScheduleEngine;
import loancashflow.model.AmortizationMethod;
import loancashflow.model.FixingObservation;
import loancashflow.model.FixingTable;
import loancashflow.model.Prepayment;
import loancashflow.model.RecastMode;
import loancashflow.model.ResetSpec;
import loancashflow.model.CashFlowRow;
import loancashflow.model.BusinessDayConvention;
import loancashflow.model.Contract;
import loancashflow.model.DayCount;
import loancashflow.model.Frequency;
import loancashflow.model.HolidayCalendar;
import loancashflow.model.RateSpec;
import loancashflow.model.ScheduleResult;
import loancashflow.model.StubConvention;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Hand-computed short-contract oracles and floating/fixing-correction cases. */
public final class ManualOracleTests {

    private final TestFramework framework;

    public ManualOracleTests(TestFramework framework) {
        this.framework = framework;
    }

    public static void main(String[] args) {
        TestFramework framework = new TestFramework();
        new ManualOracleTests(framework).run();
        framework.report();
    }

    public void run() {
        equalPrincipalOracle();
        bulletOracle();
        annuityOracle();
        fixingCorrectionMatchesFullRebuild();
        fixingCorrectionKeepsUnchangedPrefix();
        fixingVersionLatestWins();
    }

    private Contract floatingAnnuityContract(FixingTable table,
                                             java.util.List<Prepayment> prepayments) {
        return Contract.create(
                "float-oracle",
                new BigDecimal("10000.00"),
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 4, 1),
                LocalDate.of(2025, 1, 1),
                Frequency.QUARTERLY, DayCount.ACT_360,
                AmortizationMethod.ANNUITY,
                false, StubConvention.NONE, BusinessDayConvention.NONE,
                HolidayCalendar.noHolidays(),
                RateSpec.floating(new BigDecimal("0.01"), null, null,
                        new ResetSpec(LocalDate.of(2024, 1, 1), Frequency.QUARTERLY)),
                table, prepayments);
    }

    private void fixingCorrectionMatchesFullRebuild() {
        FixingTable initial = FixingTable.of(
                new FixingObservation(LocalDate.of(2024, 1, 1), new BigDecimal("0.040"), 1),
                new FixingObservation(LocalDate.of(2024, 4, 1), new BigDecimal("0.040"), 1),
                new FixingObservation(LocalDate.of(2024, 7, 1), new BigDecimal("0.040"), 1),
                new FixingObservation(LocalDate.of(2024, 10, 1), new BigDecimal("0.040"), 1));
        Contract contract = floatingAnnuityContract(initial, java.util.List.of());

        FixingObservation correction =
                new FixingObservation(LocalDate.of(2024, 4, 1), new BigDecimal("0.060"), 2);
        ScheduleEngine engine = new ScheduleEngine();
        ScheduleResult full = engine.applyFixingCorrection(contract, correction);

        java.util.List<FixingObservation> all = new java.util.ArrayList<>();
        initial.byResetDate().values().forEach(all::addAll);
        all.add(correction);
        ScheduleResult incremental =
                engine.regenerateFromFirstAffected(contract, all);

        framework.eq("same row count", full.rows().size(), incremental.rows().size());
        for (int index = 0; index < full.rows().size(); index++) {
            CashFlowRow expected = full.rows().get(index);
            CashFlowRow actual = incremental.rows().get(index);
            framework.eqMoney("incremental period " + expected.ordinal() + " rate",
                    expected.interestRate(), actual.interestRate());
            framework.eqMoney("incremental period " + expected.ordinal() + " interest",
                    expected.interest(), actual.interest());
            framework.eqMoney("incremental period " + expected.ordinal() + " principal",
                    expected.totalPrincipal(), actual.totalPrincipal());
            framework.eqMoney("incremental period " + expected.ordinal() + " closing",
                    expected.closingBalance(), actual.closingBalance());
            framework.eqMoney("incremental period " + expected.ordinal() + " payment",
                    expected.payment(), actual.payment());
        }
    }

    private void fixingCorrectionKeepsUnchangedPrefix() {
        FixingTable initial = FixingTable.of(
                new FixingObservation(LocalDate.of(2024, 1, 1), new BigDecimal("0.040"), 1),
                new FixingObservation(LocalDate.of(2024, 4, 1), new BigDecimal("0.040"), 1),
                new FixingObservation(LocalDate.of(2024, 7, 1), new BigDecimal("0.040"), 1),
                new FixingObservation(LocalDate.of(2024, 10, 1), new BigDecimal("0.040"), 1));
        Contract contract = floatingAnnuityContract(initial, java.util.List.of());
        ScheduleResult baseline = new ScheduleEngine().generate(contract);

        FixingObservation correction =
                new FixingObservation(LocalDate.of(2024, 7, 1), new BigDecimal("0.070"), 2);
        java.util.List<FixingObservation> all = new java.util.ArrayList<>();
        initial.byResetDate().values().forEach(all::addAll);
        all.add(correction);
        ScheduleResult incremental =
                new ScheduleEngine().regenerateFromFirstAffected(contract, all);

        // Periods 1-2 are governed by earlier resets and must be reused verbatim.
        framework.eq("period 1 reused", baseline.rows().get(0), incremental.rows().get(0));
        framework.eq("period 2 reused", baseline.rows().get(1), incremental.rows().get(1));
        framework.eqMoney("period 3 rate changes", new BigDecimal("0.0800000000"),
                incremental.rows().get(2).interestRate());
        framework.eqMoney("final close zero", BigDecimal.ZERO.setScale(2),
                incremental.rows().get(incremental.rows().size() - 1).closingBalance());
        assertIdentity(incremental);
    }

    private void fixingVersionLatestWins() {
        FixingTable table = FixingTable.of(
                new FixingObservation(LocalDate.of(2024, 1, 1), new BigDecimal("0.090"), 1),
                new FixingObservation(LocalDate.of(2024, 1, 1), new BigDecimal("0.030"), 3),
                new FixingObservation(LocalDate.of(2024, 1, 1), new BigDecimal("0.050"), 2),
                new FixingObservation(LocalDate.of(2024, 4, 1), new BigDecimal("0.040"), 1),
                new FixingObservation(LocalDate.of(2024, 7, 1), new BigDecimal("0.040"), 1),
                new FixingObservation(LocalDate.of(2024, 10, 1), new BigDecimal("0.040"), 1));
        ScheduleResult result = new ScheduleEngine().generate(
                floatingAnnuityContract(table, java.util.List.of()));
        framework.eqMoney("version 3 wins", new BigDecimal("0.0400000000"),
                result.rows().get(0).interestRate());
    }

    private ScheduleResult generate3Month(AmortizationMethod method) {
        Contract contract = Contract.create(
                "oracle",
                new BigDecimal("10000.00"),
                LocalDate.of(2025, 1, 15),
                LocalDate.of(2025, 2, 15),
                LocalDate.of(2025, 4, 15),
                Frequency.MONTHLY,
                DayCount.ACT_365F,
                method,
                false,
                StubConvention.NONE,
                BusinessDayConvention.NONE,
                HolidayCalendar.noHolidays(),
                RateSpec.fixed(new BigDecimal("0.12")),
                null,
                java.util.List.of());
        return new ScheduleEngine().generate(contract);
    }

    private void equalPrincipalOracle() {
        ScheduleResult result = generate3Month(AmortizationMethod.EQUAL_PRINCIPAL);
        framework.eq("EP row count", 3, result.rows().size());
        // 10000/3 = 3333.33 each period; tail 0.01 absorbed by the final period.
        framework.eqMoney("EP p1 principal", new BigDecimal("3333.33"),
                result.rows().get(0).scheduledPrincipal());
        framework.eqMoney("EP p2 principal", new BigDecimal("3333.33"),
                result.rows().get(1).scheduledPrincipal());
        framework.eqMoney("EP p3 principal", new BigDecimal("3333.34"),
                result.rows().get(2).scheduledPrincipal());
        // Interest: 12% ACT/365F. Periods 31/28/31 days with declining balances.
        framework.eqMoney("EP p1 interest",
                new BigDecimal("101.92"), result.rows().get(0).interest());
        framework.eqMoney("EP p2 interest",
                new BigDecimal("61.37"), result.rows().get(1).interest());
        framework.eqMoney("EP p3 interest",
                new BigDecimal("33.97"), result.rows().get(2).interest());
        // Balance identity and clean close.
        framework.eqMoney("EP p1 close", new BigDecimal("6666.67"),
                result.rows().get(0).closingBalance());
        framework.eqMoney("EP p2 close", new BigDecimal("3333.34"),
                result.rows().get(1).closingBalance());
        framework.eqMoney("EP p3 close", BigDecimal.ZERO.setScale(2),
                result.rows().get(2).closingBalance());
        assertIdentity(result);
    }

    private void bulletOracle() {
        ScheduleResult result = generate3Month(AmortizationMethod.BULLET);
        framework.eqMoney("B p1 principal", BigDecimal.ZERO.setScale(2),
                result.rows().get(0).scheduledPrincipal());
        framework.eqMoney("B p2 principal", BigDecimal.ZERO.setScale(2),
                result.rows().get(1).scheduledPrincipal());
        framework.eqMoney("B p3 principal", new BigDecimal("10000.00"),
                result.rows().get(2).scheduledPrincipal());
        framework.eqMoney("B p1 interest", new BigDecimal("101.92"),
                result.rows().get(0).interest());
        assertIdentity(result);
    }

    private void annuityOracle() {
        // Hand-solved level payment with flat 30/360 monthly fractions of 1/12 at 12%.
        Contract contract = Contract.create(
                "annuity-oracle",
                new BigDecimal("10000.00"),
                LocalDate.of(2025, 1, 15),
                LocalDate.of(2025, 2, 15),
                LocalDate.of(2025, 4, 15),
                Frequency.MONTHLY,
                DayCount.THIRTY_360,
                AmortizationMethod.ANNUITY,
                false,
                StubConvention.NONE,
                BusinessDayConvention.NONE,
                HolidayCalendar.noHolidays(),
                RateSpec.fixed(new BigDecimal("0.12")),
                null,
                java.util.List.of());
        ScheduleResult result = new ScheduleEngine().generate(contract);
        // Payment 3400.22: p1 interest 100.00, principal 3300.22; p2 interest 66.9978 -> 67.00,
        // principal 3333.22; final period absorbs residual: principal 3366.56, close 0.
        framework.eqMoney("A p1 payment", new BigDecimal("3400.22"),
                result.rows().get(0).payment());
        framework.eqMoney("A p1 interest", new BigDecimal("100.00"),
                result.rows().get(0).interest());
        framework.eqMoney("A p1 principal", new BigDecimal("3300.22"),
                result.rows().get(0).scheduledPrincipal());
        framework.eqMoney("A p2 interest", new BigDecimal("67.00"),
                result.rows().get(1).interest());
        framework.eqMoney("A final close", BigDecimal.ZERO.setScale(2),
                result.rows().get(2).closingBalance());
        assertIdentity(result);
    }

    static void assertIdentity(ScheduleResult result) {
        BigDecimal principal = BigDecimal.ZERO.setScale(2);
        for (var row : result.rows()) {
            BigDecimal recomputedClose = row.openingBalance()
                    .add(row.advance())
                    .subtract(row.totalPrincipal());
            if (recomputedClose.compareTo(row.closingBalance()) != 0) {
                throw new AssertionError("balance identity broken at period " + row.ordinal()
                        + ": " + recomputedClose + " != " + row.closingBalance());
            }
            if (row.closingBalance().signum() < 0) {
                throw new AssertionError("negative balance at period " + row.ordinal());
            }
            principal = principal.add(row.totalPrincipal());
        }
        if (principal.compareTo(result.contract().nominal()) != 0) {
            throw new AssertionError("principal " + principal + " != nominal "
                    + result.contract().nominal());
        }
    }
}
