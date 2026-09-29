package loanmonitor.test;

import java.util.List;

import loanmonitor.DailyOracle;
import loanmonitor.MonitoringEngine;
import loanmonitor.model.Annualization;
import loanmonitor.model.BreachKey;
import loanmonitor.model.CovenantDefinition;
import loanmonitor.model.Fact;
import loanmonitor.model.MetricSpec;
import loanmonitor.model.RoundingOrder;
import loanmonitor.model.Status;
import loanmonitor.model.TestPeriod;
import loanmonitor.model.TestType;
import loanmonitor.model.Unit;

/** Ratio/minimum/maximum boundary tests (threshold equality, scale). */
public final class MetricBoundaryTest {

    private MetricBoundaryTest() {
    }

    public static void run(TestHarness h) {
        ratioAtThresholdIsCompliant(h);
        ratioJustBelowThresholdBreaches(h);
        maximumBoundaryIsInclusive(h);
        intermediateVsFinalRoundingDiffer(h);
        thresholdScalePrecision(h);
    }

    private static TestPeriod year2024() {
        return Fixtures.period("Y2024", "2024-01-01", "2024-12-31");
    }

    private static MetricSpec dscr(RoundingOrder order) {
        return Fixtures.metric("DSCR",
                loanmonitor.model.PeriodBasis.ROLLING_12,
                Annualization.NONE, order, 2,
                Fixtures.ratio("OPERATING_INCOME", "DEBT_SERVICE"), Unit.RATIO);
    }

    private static void ratioAtThresholdIsCompliant(TestHarness h) {
        List<TestPeriod> periods = List.of(year2024());
        CovenantDefinition cov = Fixtures.covenant("COV_DSCR", TestType.RATIO,
                dscr(RoundingOrder.FINAL), "1.20", 5);
        List<Fact> facts = List.of(
                Fixtures.flow("F1", "OPERATING_INCOME", "120.00", Unit.USD,
                        "2024-01-01", "2024-12-31"),
                Fixtures.flow("F2", "DEBT_SERVICE", "100.00", Unit.USD,
                        "2024-01-01", "2024-12-31"));
        MonitoringEngine engine = Fixtures.engine(periods, List.of(cov), facts,
                List.of(), List.of());
        DailyOracle oracle = new DailyOracle(engine);
        oracle.runThrough(Fixtures.INCEPTION,
                java.time.LocalDate.of(2025, 1, 6));
        h.eq(engine.statusOf(new BreachKey("COV_DSCR", "Y2024")),
                Status.COMPLIANT, "ratio equal to threshold is compliant");
    }

    private static void ratioJustBelowThresholdBreaches(TestHarness h) {
        List<TestPeriod> periods = List.of(year2024());
        CovenantDefinition cov = Fixtures.covenant("COV_DSCR", TestType.RATIO,
                dscr(RoundingOrder.FINAL), "1.20", 0);
        List<Fact> facts = List.of(
                Fixtures.flow("F1", "OPERATING_INCOME", "119.00", Unit.USD,
                        "2024-01-01", "2024-12-31"),
                Fixtures.flow("F2", "DEBT_SERVICE", "100.00", Unit.USD,
                        "2024-01-01", "2024-12-31"));
        MonitoringEngine engine = Fixtures.engine(periods, List.of(cov), facts,
                List.of(), List.of());
        DailyOracle oracle = new DailyOracle(engine);
        oracle.runThrough(Fixtures.INCEPTION,
                java.time.LocalDate.of(2024, 12, 31));
        h.eq(engine.statusOf(new BreachKey("COV_DSCR", "Y2024")),
                Status.BREACHED, "ratio below threshold breaches on test date");
    }

    private static void maximumBoundaryIsInclusive(TestHarness h) {
        List<TestPeriod> periods = List.of(year2024());
        MetricSpec leverage = Fixtures.metric("LEVERAGE",
                loanmonitor.model.PeriodBasis.ROLLING_12,
                Annualization.NONE, RoundingOrder.FINAL, 2,
                Fixtures.ratio("TOTAL_DEBT", "EBITDA"), Unit.RATIO);
        CovenantDefinition cov = Fixtures.covenant("COV_LEV", TestType.MAXIMUM,
                leverage, "3.00", 5);
        List<Fact> facts = List.of(
                Fixtures.flow("D1", "TOTAL_DEBT", "300.00", Unit.USD,
                        "2024-01-01", "2024-12-31"),
                Fixtures.flow("E1", "EBITDA", "100.00", Unit.USD,
                        "2024-01-01", "2024-12-31"));
        MonitoringEngine engine = Fixtures.engine(periods, List.of(cov), facts,
                List.of(), List.of());
        new DailyOracle(engine).runThrough(Fixtures.INCEPTION,
                java.time.LocalDate.of(2024, 12, 31));
        h.eq(engine.statusOf(new BreachKey("COV_LEV", "Y2024")),
                Status.COMPLIANT, "maximum threshold is inclusive");
    }

    private static void intermediateVsFinalRoundingDiffer(TestHarness h) {
        List<TestPeriod> periods = List.of(year2024());
        List<Fact> facts = List.of(
                Fixtures.flow("A1", "A", "1.005", Unit.RATIO,
                        "2024-01-01", "2024-12-31"),
                Fixtures.flow("B1", "B", "2.005", Unit.RATIO,
                        "2024-01-01", "2024-12-31"));
        MetricSpec finalOrder = Fixtures.metric("SUM",
                loanmonitor.model.PeriodBasis.ROLLING_12,
                Annualization.NONE, RoundingOrder.FINAL, 1,
                new loanmonitor.model.FormulaNode.BinaryOp(
                        loanmonitor.model.FormulaNode.Operator.ADD,
                        Fixtures.ref("A", Unit.RATIO),
                        Fixtures.ref("B", Unit.RATIO)),
                Unit.RATIO);
        CovenantDefinition cov = Fixtures.covenant("COV_SUM", TestType.MINIMUM,
                finalOrder, "3.1", 5);
        MonitoringEngine engine = Fixtures.engine(periods, List.of(cov), facts,
                List.of(), List.of());
        var result = engine.evaluateMetric(cov, periods.get(0));
        h.eqBd(result.value(), "3.0",
                "final rounding of exact 3.010 gives 3.0");

        MetricSpec intermediateOrder = Fixtures.metric("SUM",
                loanmonitor.model.PeriodBasis.ROLLING_12,
                Annualization.NONE, RoundingOrder.INTERMEDIATE, 1,
                new loanmonitor.model.FormulaNode.BinaryOp(
                        loanmonitor.model.FormulaNode.Operator.ADD,
                        Fixtures.ref("A", Unit.RATIO),
                        Fixtures.ref("B", Unit.RATIO)),
                Unit.RATIO);
        CovenantDefinition cov2 = Fixtures.covenant("COV_SUM2", TestType.MINIMUM,
                intermediateOrder, "3.1", 5);
        MonitoringEngine engine2 = Fixtures.engine(periods, List.of(cov2), facts,
                List.of(), List.of());
        var result2 = engine2.evaluateMetric(cov2, periods.get(0));
        h.eqBd(result2.value(), "3.0",
                "intermediate half-up 1.0+2.0 still 3.0 at scale 1");
    }

    private static void thresholdScalePrecision(TestHarness h) {
        List<TestPeriod> periods = List.of(year2024());
        MetricSpec spec = Fixtures.metric("ICR",
                loanmonitor.model.PeriodBasis.ROLLING_12,
                Annualization.NONE, RoundingOrder.FINAL, 2,
                Fixtures.ratio("EBIT", "INTEREST"), Unit.RATIO);
        CovenantDefinition cov = Fixtures.covenant("COV_ICR", TestType.RATIO,
                spec, "2.50", 5);
        List<Fact> facts = List.of(
                Fixtures.flow("X1", "EBIT", "250.00", Unit.USD,
                        "2024-01-01", "2024-12-31"),
                Fixtures.flow("X2", "INTEREST", "100.00", Unit.USD,
                        "2024-01-01", "2024-12-31"));
        MonitoringEngine engine = Fixtures.engine(periods, List.of(cov), facts,
                List.of(), List.of());
        var result = engine.evaluateMetric(cov, periods.get(0));
        h.eq(result.value().toPlainString(), "2.50",
                "ratio result pinned to fixed scale 2");
    }
}
