package loanmonitor.test;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import loanmonitor.DailyOracle;
import loanmonitor.Loader;
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

/** Input-order independence: facts/contracts in any order give identical bytes. */
public final class CanonicalOrderTest {

    private CanonicalOrderTest() {
    }

    public static void run(TestHarness h) {
        shuffledFactAndCovenantOrderIsIdentical(h);
        loaderRecordOrderIsIrrelevant(h);
    }

    private static TestPeriod period() {
        return Fixtures.period("Y2024", "2024-01-01", "2024-12-31");
    }

    private static MetricSpec dscr() {
        return Fixtures.metric("DSCR", PeriodBasis.ROLLING_12,
                Annualization.NONE, RoundingOrder.FINAL, 2,
                Fixtures.ratio("OI", "DS"), Unit.RATIO);
    }

    private static List<Fact> facts() {
        return new java.util.ArrayList<>(List.of(
                Fixtures.flow("DS1", "DS", "100.00", Unit.USD,
                        "2024-01-01", "2024-06-30"),
                Fixtures.flow("DS2", "DS", "50.00", Unit.USD,
                        "2024-07-01", "2024-12-31"),
                Fixtures.flow("OI1", "OI", "150.00", Unit.USD,
                        "2024-01-01", "2024-06-30"),
                Fixtures.flow("OI2", "OI", "20.00", Unit.USD,
                        "2024-07-01", "2024-09-30")));
    }

    private static List<CovenantDefinition> covenants() {
        return new java.util.ArrayList<>(List.of(
                Fixtures.covenant("COV_B", TestType.MAXIMUM,
                        Fixtures.metric("LEV", PeriodBasis.ROLLING_12,
                                Annualization.NONE, RoundingOrder.FINAL, 2,
                                Fixtures.ratio("DS", "OI"), Unit.RATIO),
                        "5.00", 3),
                Fixtures.covenant("COV_A", TestType.RATIO, dscr(), "1.20", 5)));
    }

    private static String runScenario(List<Fact> facts,
                                      List<CovenantDefinition> covenants) {
        MonitoringEngine engine = Fixtures.engine(List.of(period()),
                covenants, facts, List.of(), List.of());
        new DailyOracle(engine).runThrough(Fixtures.INCEPTION,
                LocalDate.of(2025, 1, 8));
        return engine.currentSnapshot().toCanonicalText();
    }

    private static void shuffledFactAndCovenantOrderIsIdentical(TestHarness h) {
        String canonical = runScenario(facts(), covenants());

        List<Fact> reversedFacts = facts();
        Collections.reverse(reversedFacts);
        List<CovenantDefinition> reversedCovenants = covenants();
        Collections.reverse(reversedCovenants);
        String shuffled = runScenario(reversedFacts, reversedCovenants);

        h.eq(shuffled, canonical,
                "reversed fact and covenant input order gives identical bytes");
    }

    private static void loaderRecordOrderIsIrrelevant(TestHarness h) {
        String a = "meta INCEPTION 2024-01-01\n"
                + "period Y2024 2024-01-01 2024-12-31\n"
                + "covenant COV_A NORTHWIND_FICTIONAL_LTD RATIO 1.20 5\n"
                + "metric COV_A DSCR RATIO ROLLING_12 NONE FINAL 2 (/ OI@USD DS@USD)\n"
                + "fact OI1 NORTHWIND_FICTIONAL_LTD OI 170.00 USD FLOW 2024-01-01 2024-12-31 . 1\n"
                + "fact DS1 NORTHWIND_FICTIONAL_LTD DS 100.00 USD FLOW 2024-01-01 2024-12-31 . 1\n";
        String b = "fact DS1 NORTHWIND_FICTIONAL_LTD DS 100.00 USD FLOW 2024-01-01 2024-12-31 . 1\n"
                + "fact OI1 NORTHWIND_FICTIONAL_LTD OI 170.00 USD FLOW 2024-01-01 2024-12-31 . 1\n"
                + "metric COV_A DSCR RATIO ROLLING_12 NONE FINAL 2 (/ OI@USD DS@USD)\n"
                + "covenant COV_A NORTHWIND_FICTIONAL_LTD RATIO 1.20 5\n"
                + "period Y2024 2024-01-01 2024-12-31\n"
                + "meta INCEPTION 2024-01-01\n";
        Loader.LoadedInput inputA = Loader.parse(a);
        Loader.LoadedInput inputB = Loader.parse(b);

        MonitoringEngine engineA = new MonitoringEngine(inputA.inception(),
                inputA.periods(), inputA.covenants(), inputA.facts(),
                inputA.payments(), inputA.waivers());
        MonitoringEngine engineB = new MonitoringEngine(inputB.inception(),
                inputB.periods(), inputB.covenants(), inputB.facts(),
                inputB.payments(), inputB.waivers());
        new DailyOracle(engineA).runThrough(Fixtures.INCEPTION,
                LocalDate.of(2024, 12, 31));
        new DailyOracle(engineB).runThrough(Fixtures.INCEPTION,
                LocalDate.of(2024, 12, 31));
        h.eq(engineB.currentSnapshot().toCanonicalText(),
                engineA.currentSnapshot().toCanonicalText(),
                "loader accepts records in any order with identical output");
    }
}
