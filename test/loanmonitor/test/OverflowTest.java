package loanmonitor.test;

import java.math.BigDecimal;
import java.util.List;

import loanmonitor.CovenantException;
import loanmonitor.MonitoringEngine;
import loanmonitor.model.Annualization;
import loanmonitor.model.CovenantDefinition;
import loanmonitor.model.Fact;
import loanmonitor.model.FormulaNode;
import loanmonitor.model.MetricSpec;
import loanmonitor.model.PeriodBasis;
import loanmonitor.model.RoundingOrder;
import loanmonitor.model.TestPeriod;
import loanmonitor.model.TestType;
import loanmonitor.model.Unit;

/** Arithmetic overflow detection at fixed BigDecimal scale. */
public final class OverflowTest {

    private OverflowTest() {
    }

    public static void run(TestHarness h) {
        divisionExceedingDecimal128IsOverflow(h);
        hugeProductStillComputesWithinDecimal128(h);
    }

    private static TestPeriod period() {
        return Fixtures.period("P1", "2024-01-01", "2024-12-31");
    }

    private static void divisionExceedingDecimal128IsOverflow(TestHarness h) {
        MetricSpec spec = new MetricSpec("BIG_RATIO", Unit.RATIO,
                PeriodBasis.ROLLING_12, null, Annualization.NONE,
                RoundingOrder.FINAL, 2,
                Fixtures.ratio("BIG_NUM", "TINY_DEN"), 6);
        CovenantDefinition cov = Fixtures.covenant("COV_BIG", TestType.RATIO,
                spec, "1.0", 5);
        List<Fact> facts = List.of(
                Fixtures.flow("BN", "BIG_NUM", "1000000000.00", Unit.USD,
                        "2024-01-01", "2024-12-31"),
                Fixtures.flow("TD", "TINY_DEN", "0.01", Unit.USD,
                        "2024-01-01", "2024-12-31"));
        MonitoringEngine engine = Fixtures.engine(List.of(period()),
                List.of(cov), facts, List.of(), List.of());
        try {
            engine.evaluateMetric(cov, period());
            h.check(false, "ratio exceeding integer-digit limit is OVERFLOW");
        } catch (CovenantException e) {
            h.eq(e.code(), CovenantException.Code.OVERFLOW,
                    "overflow error code when fixed-scale result exceeds limit");
        }
    }

    private static void hugeProductStillComputesWithinDecimal128(
            TestHarness h) {
        MetricSpec spec = Fixtures.metric("PRODUCT", PeriodBasis.ROLLING_12,
                Annualization.NONE, RoundingOrder.FINAL, 2,
                new FormulaNode.BinaryOp(FormulaNode.Operator.MULTIPLY,
                        Fixtures.ref("A", Unit.USD),
                        new FormulaNode.Literal(new BigDecimal("2"),
                                Unit.RATIO)),
                Unit.USD);
        CovenantDefinition cov = Fixtures.covenant("COV_PROD",
                TestType.MINIMUM, spec, "0", 5);
        List<Fact> facts = List.of(
                Fixtures.flow("A1", "A", "1000000000000.00", Unit.USD,
                        "2024-01-01", "2024-12-31"));
        MonitoringEngine engine = Fixtures.engine(List.of(period()),
                List.of(cov), facts, List.of(), List.of());
        var result = engine.evaluateMetric(cov, period());
        h.eqBd(result.value(), "2000000000000.00",
                "large but in-range product computes exactly");
    }
}
