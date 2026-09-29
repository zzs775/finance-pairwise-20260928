package loanmonitor.test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import loanmonitor.MonitoringEngine;
import loanmonitor.model.Annualization;
import loanmonitor.model.CovenantDefinition;
import loanmonitor.model.Fact;
import loanmonitor.model.MetricResult;
import loanmonitor.model.MetricSpec;
import loanmonitor.model.PeriodBasis;
import loanmonitor.model.RoundingOrder;
import loanmonitor.model.TestPeriod;
import loanmonitor.model.TestType;
import loanmonitor.model.Unit;

/** Rolling-12 window vs point-in-time vs cumulative boundary semantics. */
public final class RollingWindowTest {

    private RollingWindowTest() {
    }

    public static void run(TestHarness h) {
        rollingWindowExcludesPriorYearFacts(h);
        rollingWindowIncludesStartBoundary(h);
        pointInTimeSelectsOnlyAsOf(h);
        cumulativeIncludesFromInception(h);
        annualizationActual365(h);
        annualizationTwelveMonths(h);
        provenanceListsSourcesAndFormula(h);
    }

    private static MetricSpec sumSpec(PeriodBasis basis,
                                      Annualization annualization,
                                      String account, Unit unit) {
        return Fixtures.metric("SUM_" + basis, basis, annualization,
                RoundingOrder.FINAL, 2, Fixtures.ref(account, unit), unit);
    }

    private static List<Fact> monthlyRevenue() {
        List<Fact> facts = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            LocalDate start = LocalDate.of(2024, month, 1);
            LocalDate end = start.plusMonths(1).minusDays(1);
            facts.add(Fixtures.flow("REV_" + month, "REVENUE", "10.00",
                    Unit.USD, start.toString(), end.toString()));
        }
        return facts;
    }

    private static void rollingWindowExcludesPriorYearFacts(TestHarness h) {
        TestPeriod period = Fixtures.period("P1", "2024-01-01", "2024-12-31");
        MetricSpec spec = sumSpec(PeriodBasis.ROLLING_12, Annualization.NONE,
                "REVENUE", Unit.USD);
        CovenantDefinition cov = Fixtures.covenant("COV_ROLL",
                TestType.MINIMUM, spec, "0", 5);
        List<Fact> facts = new ArrayList<>(monthlyRevenue());
        facts.add(Fixtures.flow("OLD", "REVENUE", "999.00", Unit.USD,
                "2023-12-01", "2023-12-31"));
        MonitoringEngine engine = Fixtures.engine(List.of(period), List.of(cov),
                facts, List.of(), List.of());
        MetricResult result = engine.evaluateMetric(cov, period);
        h.eqBd(result.value(), "120.00",
                "rolling-12 excludes fact ending before window start");
        h.eq(result.windowStart().toString(), "2024-01-01",
                "rolling window start is periodEnd minus one year plus one day");
    }

    private static void rollingWindowIncludesStartBoundary(TestHarness h) {
        TestPeriod period = Fixtures.period("P1", "2024-01-01", "2024-12-31");
        MetricSpec spec = sumSpec(PeriodBasis.ROLLING_12, Annualization.NONE,
                "EDGE_REV", Unit.USD);
        CovenantDefinition cov = Fixtures.covenant("COV_ROLL2",
                TestType.MINIMUM, spec, "0", 5);
        List<Fact> facts = new ArrayList<>();
        facts.add(Fixtures.flow("EDGE", "EDGE_REV", "5.00", Unit.USD,
                "2023-12-31", "2024-01-01"));
        facts.add(Fixtures.flow("INSIDE", "EDGE_REV", "7.00", Unit.USD,
                "2024-01-02", "2024-01-03"));
        facts.add(Fixtures.flow("OUTSIDE", "EDGE_REV", "100.00", Unit.USD,
                "2023-12-29", "2023-12-30"));
        MonitoringEngine engine = Fixtures.engine(List.of(period), List.of(cov),
                facts, List.of(), List.of());
        MetricResult result = engine.evaluateMetric(cov, period);
        h.eqBd(result.value(), "12.00",
                "intersecting fact at inclusive start boundary is included");
    }

    private static void pointInTimeSelectsOnlyAsOf(TestHarness h) {
        TestPeriod period = Fixtures.period("P1", "2024-01-01", "2024-06-30");
        MetricSpec spec = sumSpec(PeriodBasis.POINT_IN_TIME, Annualization.NONE,
                "CASH", Unit.USD);
        CovenantDefinition cov = Fixtures.covenant("COV_CASH",
                TestType.MINIMUM, spec, "0", 5);
        List<Fact> facts = List.of(
                Fixtures.stock("C1", "CASH", "50.00", Unit.USD, "2024-06-30"),
                Fixtures.stock("C0", "CASH", "999.00", Unit.USD, "2024-06-29"),
                Fixtures.stock("C2", "CASH", "999.00", Unit.USD, "2024-07-01"));
        MonitoringEngine engine = Fixtures.engine(List.of(period), List.of(cov),
                facts, List.of(), List.of());
        MetricResult result = engine.evaluateMetric(cov, period);
        h.eqBd(result.value(), "50.00",
                "point-in-time only selects stock at periodEnd");
    }

    private static void cumulativeIncludesFromInception(TestHarness h) {
        TestPeriod period = Fixtures.period("P1", "2024-03-01", "2024-06-30");
        MetricSpec spec = sumSpec(PeriodBasis.CUMULATIVE, Annualization.NONE,
                "REVENUE", Unit.USD);
        CovenantDefinition cov = Fixtures.covenant("COV_CUM",
                TestType.MINIMUM, spec, "0", 5);
        List<Fact> facts = List.of(
                Fixtures.flow("R1", "REVENUE", "10.00", Unit.USD,
                        "2024-01-01", "2024-01-31"),
                Fixtures.flow("R2", "REVENUE", "20.00", Unit.USD,
                        "2024-02-01", "2024-02-29"),
                Fixtures.flow("R3", "REVENUE", "30.00", Unit.USD,
                        "2024-06-01", "2024-06-30"),
                Fixtures.flow("FUTURE", "REVENUE", "999.00", Unit.USD,
                        "2024-07-01", "2024-07-31"));
        MonitoringEngine engine = Fixtures.engine(List.of(period), List.of(cov),
                facts, List.of(), List.of());
        MetricResult result = engine.evaluateMetric(cov, period);
        h.eqBd(result.value(), "60.00",
                "cumulative sums inception..periodEnd and excludes the future");
    }

    private static void annualizationActual365(TestHarness h) {
        TestPeriod period = Fixtures.period("HALF", "2024-01-01", "2024-06-30");
        MetricSpec spec = sumSpec(PeriodBasis.CUMULATIVE,
                Annualization.ACTUAL_365, "REVENUE", Unit.USD);
        CovenantDefinition cov = Fixtures.covenant("COV_ANN",
                TestType.MINIMUM, spec, "0", 5);
        List<Fact> facts = List.of(
                Fixtures.flow("H1", "REVENUE", "182.00", Unit.USD,
                        "2024-01-01", "2024-06-30"));
        MonitoringEngine engine = Fixtures.engine(List.of(period), List.of(cov),
                facts, List.of(), List.of());
        MetricResult result = engine.evaluateMetric(cov, period);
        h.eqBd(result.value(), "365.00",
                "182 over 182 days annualized actual/365 = 365.00");
    }

    private static void annualizationTwelveMonths(TestHarness h) {
        TestPeriod period = Fixtures.period("Q1", "2024-01-01", "2024-03-31");
        MetricSpec spec = sumSpec(PeriodBasis.CUMULATIVE,
                Annualization.TWELVE_MONTHS, "REVENUE", Unit.USD);
        CovenantDefinition cov = Fixtures.covenant("COV_ANN12",
                TestType.MINIMUM, spec, "0", 5);
        List<Fact> facts = List.of(
                Fixtures.flow("H1", "REVENUE", "30.00", Unit.USD,
                        "2024-01-01", "2024-03-31"));
        MonitoringEngine engine = Fixtures.engine(List.of(period), List.of(cov),
                facts, List.of(), List.of());
        MetricResult result = engine.evaluateMetric(cov, period);
        h.eqBd(result.value(), "120.00",
                "one quarter 30 annualized x12/3 = 120.00");
    }

    private static void provenanceListsSourcesAndFormula(TestHarness h) {
        TestPeriod period = Fixtures.period("P1", "2024-01-01", "2024-12-31");
        MetricSpec spec = Fixtures.metric("DSCR", PeriodBasis.ROLLING_12,
                Annualization.NONE, RoundingOrder.FINAL, 2,
                Fixtures.ratio("OI", "DS"), Unit.RATIO);
        CovenantDefinition cov = Fixtures.covenant("COV_PROV",
                TestType.RATIO, spec, "1.0", 5);
        List<Fact> facts = List.of(
                Fixtures.flow("OI1", "OI", "150.00", Unit.USD,
                        "2024-01-01", "2024-12-31"),
                Fixtures.flow("DS1", "DS", "100.00", Unit.USD,
                        "2024-01-01", "2024-12-31"));
        MonitoringEngine engine = Fixtures.engine(List.of(period), List.of(cov),
                facts, List.of(), List.of());
        MetricResult result = engine.evaluateMetric(cov, period);
        h.eq(result.sources().size(), 2, "both fact sources recorded");
        h.check(result.formulaTrace().contains("DIVIDE("),
                "formula tree trace contains the DIVIDE step");
        h.check(result.formulaTrace().contains("OI1")
                        || result.sources().get(0).factId().equals("OI1"),
                "provenance identifies source fact ids");
    }
}
