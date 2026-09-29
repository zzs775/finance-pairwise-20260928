package loanmonitor.test;

import java.util.List;

import loanmonitor.CovenantException;
import loanmonitor.MonitoringEngine;
import loanmonitor.model.Annualization;
import loanmonitor.model.CovenantDefinition;
import loanmonitor.model.Fact;
import loanmonitor.model.MetricSpec;
import loanmonitor.model.PeriodBasis;
import loanmonitor.model.RoundingOrder;
import loanmonitor.model.TestPeriod;
import loanmonitor.model.TestType;
import loanmonitor.model.Unit;

/** Missing facts, missing definitions, division by zero, unit conflicts. */
public final class MissingFactTest {

    private MissingFactTest() {
    }

    public static void run(TestHarness h) {
        missingFactIsClassified(h);
        divisionByZeroIsClassified(h);
        unitConflictInFormulaIsClassified(h);
        duplicateFactIsClassified(h);
        periodOverlapIsClassified(h);
        missingMetricDefinitionIsClassified(h);
    }

    private static TestPeriod period() {
        return Fixtures.period("P1", "2024-01-01", "2024-12-31");
    }

    private static void missingFactIsClassified(TestHarness h) {
        MetricSpec spec = Fixtures.metric("M", PeriodBasis.ROLLING_12,
                Annualization.NONE, RoundingOrder.FINAL, 2,
                Fixtures.ref("MISSING_ACCOUNT", Unit.USD), Unit.USD);
        CovenantDefinition cov = Fixtures.covenant("COV_MISSING",
                TestType.MINIMUM, spec, "0", 5);
        MonitoringEngine engine = Fixtures.engine(List.of(period()),
                List.of(cov), List.of(), List.of(), List.of());
        h.expectThrows(CovenantException.class,
                () -> engine.evaluateMetric(cov, period()),
                "missing fact yields MISSING_DEFINITION");
        try {
            engine.evaluateMetric(cov, period());
        } catch (CovenantException e) {
            h.eq(e.code(), CovenantException.Code.MISSING_DEFINITION,
                    "missing fact error code");
        }
    }

    private static void divisionByZeroIsClassified(TestHarness h) {
        MetricSpec spec = Fixtures.metric("R", PeriodBasis.ROLLING_12,
                Annualization.NONE, RoundingOrder.FINAL, 2,
                Fixtures.ratio("NUM", "DEN"), Unit.RATIO);
        CovenantDefinition cov = Fixtures.covenant("COV_DIV0",
                TestType.RATIO, spec, "1.0", 5);
        List<Fact> facts = List.of(
                Fixtures.flow("N", "NUM", "100.00", Unit.USD,
                        "2024-01-01", "2024-12-31"),
                Fixtures.flow("D", "DEN", "0.00", Unit.USD,
                        "2024-01-01", "2024-12-31"));
        MonitoringEngine engine = Fixtures.engine(List.of(period()),
                List.of(cov), facts, List.of(), List.of());
        h.expectThrows(CovenantException.class,
                () -> engine.evaluateMetric(cov, period()),
                "zero denominator yields DIVISION_BY_ZERO");
        try {
            engine.evaluateMetric(cov, period());
        } catch (CovenantException e) {
            h.eq(e.code(), CovenantException.Code.DIVISION_BY_ZERO,
                    "division error code");
        }
    }

    private static void unitConflictInFormulaIsClassified(TestHarness h) {
        MetricSpec spec = Fixtures.metric("BAD", PeriodBasis.ROLLING_12,
                Annualization.NONE, RoundingOrder.FINAL, 2,
                new loanmonitor.model.FormulaNode.BinaryOp(
                        loanmonitor.model.FormulaNode.Operator.ADD,
                        Fixtures.ref("USD_ACCOUNT", Unit.USD),
                        Fixtures.ref("DAYS_ACCOUNT", Unit.DAYS)),
                Unit.USD);
        CovenantDefinition cov = Fixtures.covenant("COV_UNIT",
                TestType.MINIMUM, spec, "0", 5);
        List<Fact> facts = List.of(
                Fixtures.flow("U1", "USD_ACCOUNT", "100.00", Unit.USD,
                        "2024-01-01", "2024-12-31"),
                Fixtures.flow("U2", "DAYS_ACCOUNT", "12.00", Unit.DAYS,
                        "2024-01-01", "2024-12-31"));
        MonitoringEngine engine = Fixtures.engine(List.of(period()),
                List.of(cov), facts, List.of(), List.of());
        try {
            engine.evaluateMetric(cov, period());
            h.check(false, "unit conflict throws UNIT_CONFLICT");
        } catch (CovenantException e) {
            h.eq(e.code(), CovenantException.Code.UNIT_CONFLICT,
                    "unit conflict error code");
        }
    }

    private static void duplicateFactIsClassified(TestHarness h) {
        List<Fact> facts = List.of(
                Fixtures.flow("A", "ACC", "10.00", Unit.USD,
                        "2024-01-01", "2024-06-30"),
                Fixtures.flow("B", "ACC", "20.00", Unit.USD,
                        "2024-01-01", "2024-06-30"));
        try {
            Fixtures.engine(List.of(period()), List.of(), facts, List.of(),
                    List.of());
            h.check(false, "identical logical key/version rejected");
        } catch (CovenantException e) {
            h.eq(e.code(), CovenantException.Code.DUPLICATE_FACT,
                    "duplicate fact error code");
        }
    }

    private static void periodOverlapIsClassified(TestHarness h) {
        List<TestPeriod> periods = List.of(
                Fixtures.period("P1", "2024-01-01", "2024-06-30"),
                Fixtures.period("P2", "2024-06-01", "2024-12-31"));
        try {
            Fixtures.engine(periods, List.of(), List.of(), List.of(), List.of());
            h.check(false, "overlapping test periods rejected");
        } catch (CovenantException e) {
            h.eq(e.code(), CovenantException.Code.PERIOD_OVERLAP,
                    "period overlap error code");
        }
    }

    private static void missingMetricDefinitionIsClassified(TestHarness h) {
        CovenantDefinition cov = Fixtures.covenant("COV_NOMETRIC",
                TestType.RATIO, null, "1.0", 5);
        try {
            Fixtures.engine(List.of(period()), List.of(cov), List.of(),
                    List.of(), List.of());
            h.check(false, "ratio covenant without metric rejected");
        } catch (CovenantException e) {
            h.eq(e.code(), CovenantException.Code.MISSING_DEFINITION,
                    "missing metric definition error code");
        }
    }
}
