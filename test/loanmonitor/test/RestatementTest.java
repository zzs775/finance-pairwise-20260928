package loanmonitor.test;

import java.util.List;

import loanmonitor.CovenantException;
import loanmonitor.DailyOracle;
import loanmonitor.MonitoringEngine;
import loanmonitor.RestatementManager;
import loanmonitor.model.Annualization;
import loanmonitor.model.BreachKey;
import loanmonitor.model.CovenantDefinition;
import loanmonitor.model.Fact;
import loanmonitor.model.MetricResult;
import loanmonitor.model.MetricSpec;
import loanmonitor.model.PeriodBasis;
import loanmonitor.model.RoundingOrder;
import loanmonitor.model.Status;
import loanmonitor.model.TestPeriod;
import loanmonitor.model.TestType;
import loanmonitor.model.Unit;

/** Versioned restatement, earliest-affected recompute, failed restatement. */
public final class RestatementTest {

    private RestatementTest() {
    }

    public static void run(TestHarness h) {
        restatementCreatesNewVersionAndRepairsBreach(h);
        earliestAffectedPeriodIdentified(h);
        incrementalEqualsFullRebuildByteForByte(h);
        failedRestatementKeepsPublishedSnapshot(h);
        restatementDoesNotEraseHistoricalEvidence(h);
    }

    private static TestPeriod period(String label, int quarter) {
        return Fixtures.quarter(label, quarter);
    }

    private static MetricSpec cashSpec() {
        return Fixtures.metric("MIN_CASH", PeriodBasis.POINT_IN_TIME,
                Annualization.NONE, RoundingOrder.FINAL, 2,
                Fixtures.ref("CASH", Unit.USD), Unit.USD);
    }

    private static CovenantDefinition cashCovenant(String id) {
        return Fixtures.covenant(id, TestType.MINIMUM, cashSpec(), "100.00", 0);
    }

    private static void restatementCreatesNewVersionAndRepairsBreach(
            TestHarness h) {
        TestPeriod p1 = period("P1", 1);
        TestPeriod p2 = period("P2", 2);
        List<TestPeriod> periods = List.of(p1, p2);
        Fact cashQ1 = Fixtures.stockV("CASH_Q1", "CASH", "50.00", Unit.USD,
                p1.periodEnd().toString(), 1);
        Fact cashQ2 = Fixtures.stockV("CASH_Q2", "CASH", "150.00", Unit.USD,
                p2.periodEnd().toString(), 1);
        MonitoringEngine engine = Fixtures.engine(periods,
                List.of(cashCovenant("COV_CASH")),
                List.of(cashQ1, cashQ2), List.of(), List.of());
        new DailyOracle(engine).runThrough(Fixtures.INCEPTION,
                java.time.LocalDate.of(2024, 6, 30));
        h.eq(engine.statusOf(new BreachKey("COV_CASH", "P1")),
                Status.DEFAULTED, "Q1 cash shortfall defaults with zero grace");

        RestatementManager manager = new RestatementManager(engine);
        Fact corrected = Fixtures.stockV("CASH_Q1", "CASH", "120.00", Unit.USD,
                p1.periodEnd().toString(), 2);
        var result = manager.restate(corrected);
        h.eq(result.earliestAffectedPeriod(), "P1",
                "restatement anchors at earliest affected period P1");
        MetricResult q1 = engine.currentSnapshot().metrics()
                .get(new BreachKey("COV_CASH", "P1"));
        h.eqBd(q1.value(), "120.00", "restated value recomputed");
        h.eq(q1.sources().get(0).version(), 2,
                "provenance points at the new fact version");
    }

    private static void earliestAffectedPeriodIdentified(TestHarness h) {
        TestPeriod p1 = period("P1", 1);
        TestPeriod p2 = period("P2", 2);
        MonitoringEngine engine = Fixtures.engine(List.of(p1, p2),
                List.of(cashCovenant("COV_CASH")), List.of(), List.of(),
                List.of());
        RestatementManager manager = new RestatementManager(engine);
        Fact correctedQ2 = Fixtures.stockV("CASH_Q2", "CASH", "120.00",
                Unit.USD, p2.periodEnd().toString(), 2);
        h.eq(manager.earliestAffectedPeriod(correctedQ2), "P2",
                "Q2 fact anchors recompute at P2, not P1");
    }

    private static void incrementalEqualsFullRebuildByteForByte(TestHarness h) {
        TestPeriod p1 = period("P1", 1);
        TestPeriod p2 = period("P2", 2);
        List<TestPeriod> periods = List.of(p1, p2);
        Fact cashQ1 = Fixtures.stockV("CASH_Q1", "CASH", "50.00", Unit.USD,
                p1.periodEnd().toString(), 1);
        Fact cashQ2 = Fixtures.stockV("CASH_Q2", "CASH", "150.00", Unit.USD,
                p2.periodEnd().toString(), 1);
        MonitoringEngine engine = Fixtures.engine(periods,
                List.of(cashCovenant("COV_CASH")),
                List.of(cashQ1, cashQ2), List.of(), List.of());
        RestatementManager manager = new RestatementManager(engine);
        Fact corrected = Fixtures.stockV("CASH_Q1", "CASH", "120.00", Unit.USD,
                p1.periodEnd().toString(), 2);
        var incremental = manager.restate(corrected);

        var fullRebuild = manager.fullRebuild();

        String incrementalBody = stripRestatementAlerts(incremental.snapshot());
        String rebuiltBody = stripRestatementAlerts(fullRebuild);
        h.eq(incrementalBody, rebuiltBody,
                "incremental and full rebuild canonical bodies are byte-identical");
    }

    private static void failedRestatementKeepsPublishedSnapshot(TestHarness h) {
        TestPeriod p1 = period("P1", 1);
        Fact cashQ1 = Fixtures.stockV("CASH_Q1", "CASH", "50.00", Unit.USD,
                p1.periodEnd().toString(), 1);
        MonitoringEngine engine = Fixtures.engine(List.of(p1),
                List.of(cashCovenant("COV_CASH")), List.of(cashQ1), List.of(),
                List.of());
        new DailyOracle(engine).runThrough(Fixtures.INCEPTION,
                java.time.LocalDate.of(2024, 3, 31));
        String publishedBefore = engine.currentSnapshot().toCanonicalText();

        RestatementManager manager = new RestatementManager(engine);
        Fact bad = Fixtures.stockV("CASH_Q1", "CASH", "50.00", Unit.DAYS,
                p1.periodEnd().toString(), 2);
        h.expectThrows(CovenantException.class, () -> manager.restate(bad),
                "unit-changing restatement fails");
        String publishedAfter = engine.currentSnapshot().toCanonicalText();
        h.eq(publishedAfter, publishedBefore,
                "failed restatement never replaces published snapshot");
        MetricResult q1 = engine.currentSnapshot().metrics()
                .get(new BreachKey("COV_CASH", "P1"));
        h.eq(q1.sources().get(0).version(), 1,
                "failed restatement does not change active fact version");
    }

    private static void restatementDoesNotEraseHistoricalEvidence(
            TestHarness h) {
        TestPeriod p1 = period("P1", 1);
        Fact cashQ1 = Fixtures.stockV("CASH_Q1", "CASH", "50.00", Unit.USD,
                p1.periodEnd().toString(), 1);
        MonitoringEngine engine = Fixtures.engine(List.of(p1),
                List.of(cashCovenant("COV_CASH")), List.of(cashQ1), List.of(),
                List.of());
        new DailyOracle(engine).runThrough(Fixtures.INCEPTION,
                java.time.LocalDate.of(2024, 3, 31));
        int breachesBefore = engine.currentSnapshot().breaches().size();

        RestatementManager manager = new RestatementManager(engine);
        manager.restate(Fixtures.stockV("CASH_Q1", "CASH", "120.00", Unit.USD,
                p1.periodEnd().toString(), 2));
        h.eq(engine.currentSnapshot().breaches().size(), breachesBefore,
                "historical breach evidence survives the restatement");
    }

    private static String stripRestatementAlerts(
            loanmonitor.model.MonitoringSnapshot snapshot) {
        var filtered = snapshot.alerts().stream()
                .filter(a -> a.type() != loanmonitor.model.AlertType.RESTATEMENT)
                .toList();
        var cleaned = new loanmonitor.model.MonitoringSnapshot(
                snapshot.asOf(), 0, snapshot.statuses(),
                snapshot.metrics(), snapshot.breaches(), filtered);
        return cleaned.toCanonicalText();
    }
}
