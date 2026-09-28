package covenant.tests;

import covenant.Alert;
import covenant.AlertType;
import covenant.Annualization;
import covenant.CovenantTest;
import covenant.DivisionByZeroException;
import covenant.DuplicateFactException;
import covenant.Engine;
import covenant.Evaluator;
import covenant.ExplainedValue;
import covenant.Fact;
import covenant.FactStore;
import covenant.MetricCatalog;
import covenant.MetricCalculator;
import covenant.MetricSpec;
import covenant.MissingDefinitionException;
import covenant.Operator;
import covenant.OverflowException;
import covenant.OverlappingPeriodException;
import covenant.Oracle;
import covenant.PaymentEvent;
import covenant.PeriodBasis;
import covenant.Periods;
import covenant.RestatementResult;
import covenant.RoundingOrder;
import covenant.SimulationClock;
import covenant.Snapshot;
import covenant.Status;
import covenant.TestKind;
import covenant.TestResult;
import covenant.Unit;
import covenant.UnitConflictException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class SelfTest {

    private static final String BORROWER = "NORTHWIND FICTIONAL LTD";
    private static final Unit USD = Unit.money("USD");
    private static final Unit RATIO = Unit.ratio();

    private final List<String> failures = new ArrayList<>();
    private int checks = 0;

    public static void main(String[] args) {
        boolean ok = run();
        System.out.println(ok ? "SELFTEST PASS" : "SELFTEST FAIL");
        if (!ok) {
            System.exit(1);
        }
    }

    public static boolean run() {
        SelfTest test = new SelfTest();
        test.testRatioBoundary();
        test.testFixedScaleAndArithmetic();
        test.testRollingWindow();
        test.testPointInTimeAndCumulativeDistinct();
        test.testDuplicateFact();
        test.testUnitConflict();
        test.testOverlappingPeriods();
        test.testDivisionByZero();
        test.testMissingDefinition();
        test.testOverflow();
        test.testGraceExpiryStateMachine();
        test.testLocalWaiverKeepsEvidence();
        test.testPaymentDateTest();
        test.testRestatement();
        test.testOrderIndependence();
        test.report();
        return test.failures.isEmpty();
    }

    private void check(boolean condition, String name) {
        checks++;
        if (!condition) {
            failures.add(name);
        }
    }

    private void checkEq(Object actual, Object expected, String name) {
        checks++;
        boolean equal;
        if (actual instanceof BigDecimal a && expected instanceof BigDecimal e) {
            equal = a.compareTo(e) == 0;
        } else {
            equal = java.util.Objects.equals(actual, expected);
        }
        if (!equal) {
            failures.add(name + " expected=[" + expected + "] actual=[" + actual + "]");
        }
    }

    private static Fact fact(String metric, LocalDate start, LocalDate end,
                             String value, String version, Unit unit) {
        return new Fact(BORROWER, metric, unit, start, end, new BigDecimal(value),
                version, end, "ledger:" + metric + ":" + start);
    }

    private static MetricSpec quarterly(String metric, Unit unit) {
        return new MetricSpec(metric, unit, PeriodBasis.CUMULATIVE, Annualization.NONE,
                RoundingOrder.ROUND_AFTER_ANNUALIZE, 2,
                new covenant.Expr.FactRef(metric));
    }

    private static MetricSpec pointMetric(String metric) {
        return new MetricSpec(metric, USD, PeriodBasis.POINT_IN_TIME, Annualization.NONE,
                RoundingOrder.ROUND_AFTER_ANNUALIZE, 2,
                new covenant.Expr.FactRef(metric));
    }

    private void testRatioBoundary() {
        LocalDate q1Start = LocalDate.of(2025, 1, 1);
        LocalDate q1End = LocalDate.of(2025, 4, 1);
        MetricSpec dscr = new MetricSpec("dscr", RATIO, PeriodBasis.CUMULATIVE,
                Annualization.NONE, RoundingOrder.ROUND_AFTER_ANNUALIZE, 4,
                new covenant.Expr.BinaryOp(Operator.DIVIDE,
                        new covenant.Expr.FactRef("ebitda"),
                        new covenant.Expr.FactRef("debtService")));
        MetricCatalog catalog = new MetricCatalog(List.of(
                quarterly("ebitda", USD), quarterly("debtService", USD), dscr));
        FactStore store = new FactStore();
        store.add(fact("ebitda", q1Start, q1End, "100.00", "v1", USD), USD);
        store.add(fact("debtService", q1Start, q1End, "80.00", "v1", USD), USD);
        MetricCalculator calculator = new MetricCalculator(store, catalog);
        ExplainedValue atBoundary = calculator.calculate(BORROWER, dscr, q1Start, q1End);
        checkEq(atBoundary.value(), new BigDecimal("1.25"), "ratio boundary 100/80");

        FactStore storeBelow = new FactStore();
        storeBelow.add(fact("ebitda", q1Start, q1End, "99.99", "v1", USD), USD);
        storeBelow.add(fact("debtService", q1Start, q1End, "80.00", "v1", USD), USD);
        ExplainedValue below = new MetricCalculator(storeBelow, catalog)
                .calculate(BORROWER, dscr, q1Start, q1End);
        check(below.value().compareTo(new BigDecimal("1.25")) < 0,
                "ratio strictly below threshold");
        CovenantTest test = new CovenantTest("t_dscr", TestKind.RATIO, null,
                "dscr_num", "dscr_den", new BigDecimal("1.25"), RATIO, 0,
                null, true);
        check(atBoundary.formulaTree().contains("ledger:ebitda"),
                "formula tree lists fact source");
    }

    private void testFixedScaleAndArithmetic() {
        LocalDate q1Start = LocalDate.of(2025, 1, 1);
        LocalDate q1End = LocalDate.of(2025, 4, 1);
        MetricSpec before = new MetricSpec("ar_b", USD, PeriodBasis.CUMULATIVE,
                Annualization.ACT_365F, RoundingOrder.ROUND_BEFORE_ANNUALIZE, 0,
                new covenant.Expr.FactRef("ar_raw"));
        MetricSpec after = new MetricSpec("ar_a", USD, PeriodBasis.CUMULATIVE,
                Annualization.ACT_365F, RoundingOrder.ROUND_AFTER_ANNUALIZE, 0,
                new covenant.Expr.FactRef("ar_raw"));
        MetricCatalog catalog = new MetricCatalog(List.of(
                new MetricSpec("ar_raw", USD, PeriodBasis.CUMULATIVE, Annualization.NONE,
                        RoundingOrder.ROUND_AFTER_ANNUALIZE, 2,
                        new covenant.Expr.FactRef("ar_raw")),
                before, after));
        FactStore store = new FactStore();
        store.add(fact("ar_raw", q1Start, q1End, "10.49", "v1", USD), USD);
        MetricCalculator calculator = new MetricCalculator(store, catalog);
        long days = java.time.temporal.ChronoUnit.DAYS.between(q1Start, q1End);
        // ROUND_BEFORE_ANNUALIZE: 10.49 -> 10 (HALF_UP at scale 0), then 10*365/90.
        BigDecimal factor = BigDecimal.valueOf(365L)
                .divide(BigDecimal.valueOf(days), 20, MetricCalculator.ROUNDING);
        BigDecimal expectedBefore = new BigDecimal("10").multiply(factor)
                .setScale(0, MetricCalculator.ROUNDING);
        BigDecimal expectedAfter = new BigDecimal("10.49")
                .multiply(factor)
                .setScale(0, MetricCalculator.ROUNDING);
        checkEq(calculator.calculate(BORROWER, before, q1Start, q1End).value(),
                expectedBefore, "round before annualize");
        checkEq(calculator.calculate(BORROWER, after, q1Start, q1End).value(),
                expectedAfter, "round after annualize");
        check(!java.util.Objects.equals(expectedBefore, expectedAfter),
                "rounding orders differ");
    }

    private void testRollingWindow() {
        MetricSpec monthlyEbitda = new MetricSpec("ebitda_m", USD, PeriodBasis.CUMULATIVE,
                Annualization.NONE, RoundingOrder.ROUND_AFTER_ANNUALIZE, 2,
                new covenant.Expr.FactRef("ebitda_m"));
        MetricSpec rolling = new MetricSpec("ebitda_ttm", USD, PeriodBasis.ROLLING_12,
                Annualization.NONE, RoundingOrder.ROUND_AFTER_ANNUALIZE, 2,
                new covenant.Expr.FactRef("ebitda_m"));
        MetricCatalog catalog = new MetricCatalog(List.of(monthlyEbitda, rolling));
        FactStore store = new FactStore();
        LocalDate cursor = LocalDate.of(2024, 1, 1);
        for (int month = 0; month < 14; month++) {
            LocalDate start = cursor.plusMonths(month);
            LocalDate end = start.plusMonths(1);
            store.add(fact("ebitda_m", start, end, "10.00", "v1", USD), USD);
        }
        MetricCalculator calculator = new MetricCalculator(store, catalog);
        LocalDate windowEnd = LocalDate.of(2025, 1, 1);
        ExplainedValue ttm = calculator.calculate(BORROWER, rolling,
                windowEnd.minusMonths(3), windowEnd);
        checkEq(ttm.value(), new BigDecimal("120.00"), "rolling 12 sums 12 monthly facts");
        checkEq(BigDecimal.valueOf(ttm.sources().size()), BigDecimal.valueOf(12),
                "rolling window selects exactly 12 sources");
        // The month ending exactly at window start (2023-12-01..2024-01-01) is excluded.
        ExplainedValue shifted = calculator.calculate(BORROWER, rolling,
                LocalDate.of(2024, 2, 1), LocalDate.of(2025, 2, 1));
        checkEq(shifted.value(), new BigDecimal("120.00"),
                "rolling window slides by month");
    }

    private void testPointInTimeAndCumulativeDistinct() {
        LocalDate q1 = LocalDate.of(2025, 1, 1);
        LocalDate q2 = LocalDate.of(2025, 4, 1);
        LocalDate h1 = LocalDate.of(2025, 7, 1);
        MetricSpec cashPoint = new MetricSpec("cash", USD, PeriodBasis.POINT_IN_TIME,
                Annualization.NONE, RoundingOrder.ROUND_AFTER_ANNUALIZE, 2,
                new covenant.Expr.FactRef("cash"));
        MetricSpec revenueCum = new MetricSpec("revenue", USD, PeriodBasis.CUMULATIVE,
                Annualization.NONE, RoundingOrder.ROUND_AFTER_ANNUALIZE, 2,
                new covenant.Expr.FactRef("revenue"));
        MetricCatalog catalog = new MetricCatalog(List.of(cashPoint, revenueCum));
        FactStore store = new FactStore();
        store.add(fact("cash", q2, h1, "500.00", "v1", USD), USD);
        store.add(fact("revenue", q1, q2, "100.00", "v1", USD), USD);
        store.add(fact("revenue", q2, h1, "120.00", "v1", USD), USD);
        MetricCalculator calculator = new MetricCalculator(store, catalog);
        boolean pointMissingForQ1 = false;
        try {
            calculator.calculate(BORROWER, cashPoint, q1, q2);
        } catch (MissingDefinitionException e) {
            pointMissingForQ1 = true;
        }
        check(pointMissingForQ1, "point-in-time requires exact-period fact (no summing)");
        checkEq(calculator.calculate(BORROWER, cashPoint, q2, h1).value(),
                new BigDecimal("500.00"), "point-in-time reads exact period only");
        checkEq(calculator.calculate(BORROWER, revenueCum, q1, h1).value(),
                new BigDecimal("220.00"), "cumulative sums contained periods");
    }

    private void testDuplicateFact() {
        FactStore store = new FactStore();
        LocalDate start = LocalDate.of(2025, 1, 1);
        LocalDate end = LocalDate.of(2025, 4, 1);
        store.add(fact("ebitda", start, end, "10", "v1", USD), USD);
        boolean caught = false;
        try {
            store.add(fact("ebitda", start, end, "11", "v1", USD), USD);
        } catch (DuplicateFactException e) {
            caught = true;
        }
        check(caught, "duplicate fact same version rejected");
    }

    private void testUnitConflict() {
        FactStore store = new FactStore();
        LocalDate start = LocalDate.of(2025, 1, 1);
        LocalDate end = LocalDate.of(2025, 4, 1);
        store.add(fact("cash", start, end, "10", "v1", USD), USD);
        boolean caught = false;
        try {
            store.add(fact("cash", start, end, "10", "v2", Unit.money("EUR")), USD);
        } catch (UnitConflictException e) {
            caught = true;
        }
        check(caught, "same metric restated in different currency rejected");
        check(!USD.compatibleWith(Unit.count()), "money incompatible with count");
    }

    private void testOverlappingPeriods() {
        FactStore store = new FactStore();
        store.add(fact("revenue", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 4, 1),
                "10", "v1", USD), USD);
        boolean touchingOk = true;
        try {
            // Touching at endpoint is not an overlap: [Jan1,Apr1) then [Apr1,Jul1).
            store.add(fact("revenue", LocalDate.of(2025, 4, 1), LocalDate.of(2025, 7, 1),
                    "20", "v1", USD), USD);
        } catch (OverlappingPeriodException e) {
            touchingOk = false;
        }
        check(touchingOk, "adjacent half-open periods accepted");
        boolean overlapCaught = false;
        try {
            store.add(fact("revenue", LocalDate.of(2025, 2, 1), LocalDate.of(2025, 5, 1),
                    "30", "v1", USD), USD);
        } catch (OverlappingPeriodException e) {
            overlapCaught = true;
        }
        check(overlapCaught, "overlapping distinct periods rejected");
    }

    private void testDivisionByZero() {
        LocalDate start = LocalDate.of(2025, 1, 1);
        LocalDate end = LocalDate.of(2025, 4, 1);
        MetricSpec ratio = new MetricSpec("leverage", RATIO, PeriodBasis.CUMULATIVE,
                Annualization.NONE, RoundingOrder.ROUND_AFTER_ANNUALIZE, 4,
                new covenant.Expr.BinaryOp(Operator.DIVIDE,
                        new covenant.Expr.FactRef("debt"),
                        new covenant.Expr.FactRef("equity")));
        MetricCatalog catalog = new MetricCatalog(List.of(
                quarterly("debt", USD), quarterly("equity", USD), ratio));
        FactStore store = new FactStore();
        store.add(fact("debt", start, end, "100.00", "v1", USD), USD);
        store.add(fact("equity", start, end, "0.00", "v1", USD), USD);
        boolean caught = false;
        try {
            new MetricCalculator(store, catalog).calculate(BORROWER, ratio, start, end);
        } catch (DivisionByZeroException e) {
            caught = true;
        }
        check(caught, "division by zero classified");
    }

    private void testMissingDefinition() {
        boolean catalogCaught = false;
        try {
            new MetricCatalog(List.of()).get("ghost");
        } catch (MissingDefinitionException e) {
            catalogCaught = true;
        }
        check(catalogCaught, "missing metric definition classified");
        LocalDate start = LocalDate.of(2025, 1, 1);
        LocalDate end = LocalDate.of(2025, 4, 1);
        MetricSpec spec = quarterly("sales", USD);
        MetricCalculator calculator = new MetricCalculator(new FactStore(),
                new MetricCatalog(List.of(spec)));
        boolean factCaught = false;
        try {
            calculator.calculate(BORROWER, spec, start, end);
        } catch (MissingDefinitionException e) {
            factCaught = true;
        }
        check(factCaught, "missing fact classified");
    }

    private void testOverflow() {
        LocalDate start = LocalDate.of(2025, 1, 1);
        LocalDate end = LocalDate.of(2025, 4, 1);
        MetricSpec huge = new MetricSpec("huge", USD, PeriodBasis.CUMULATIVE,
                Annualization.NONE, RoundingOrder.ROUND_AFTER_ANNUALIZE, 2,
                new covenant.Expr.BinaryOp(Operator.MULTIPLY,
                        new covenant.Expr.FactRef("a"),
                        new covenant.Expr.FactRef("b")));
        MetricCatalog catalog = new MetricCatalog(List.of(
                quarterly("a", USD), quarterly("b", USD), huge));
        FactStore store = new FactStore();
        store.add(fact("a", start, end, "1000000000", "v1", USD), USD);
        store.add(fact("b", start, end, "1000000000", "v1", USD), USD);
        boolean caught = false;
        try {
            new MetricCalculator(store, catalog).calculate(BORROWER, huge, start, end);
        } catch (OverflowException e) {
            caught = true;
        }
        check(caught, "overflow beyond 1E18 classified");
    }

    private void testGraceExpiryStateMachine() {
        LocalDate q1Start = LocalDate.of(2025, 1, 1);
        LocalDate q1End = LocalDate.of(2025, 4, 1);
        MetricSpec ebitda = quarterly("ebitda", USD);
        MetricCatalog catalog = new MetricCatalog(List.of(ebitda));
        CovenantTest minimum = new CovenantTest("t_min_ebitda", TestKind.MINIMUM,
                "ebitda", null, null, new BigDecimal("50.00"), USD, 10,
                null, true);
        FactStore store = new FactStore();
        store.add(fact("ebitda", q1Start, q1End, "10.00", "v1", USD), USD);
        Evaluator evaluator = new Evaluator(store, catalog, List.of(minimum),
                List.of(), List.of());
        SimulationClock clock = new SimulationClock(q1End);
        Oracle oracle = new Oracle(evaluator, new covenant.StateMachine(), clock);
        oracle.registerBorrowerPeriods(BORROWER, q1Start, 3, 1);

        oracle.advanceTo(q1End);
        check(statusAt(oracle, "t_min_ebitda", q1Start, q1End) == Status.CURE_PENDING,
                "breach enters cure_pending on period end");

        oracle.advanceTo(q1End.plusDays(10));
        check(statusAt(oracle, "t_min_ebitda", q1Start, q1End) == Status.CURE_PENDING,
                "still within grace on last grace day");

        oracle.advanceTo(q1End.plusDays(11));
        check(statusAt(oracle, "t_min_ebitda", q1Start, q1End) == Status.DEFAULTED,
                "defaulted the day after grace ends");

        List<AlertType> types = oracle.alerts().stream().map(Alert::type).toList();
        check(types.contains(AlertType.CURE_PENDING), "CURE_PENDING alert emitted");
        check(types.contains(AlertType.DEFAULTED), "DEFAULTED alert emitted");
        check(oracle.alerts().stream()
                        .filter(a -> a.type() == AlertType.DEFAULTED)
                        .findFirst().orElseThrow().date().equals(q1End.plusDays(11)),
                "default alert dated on injected logical date");
    }

    private Status statusAt(Oracle oracle, String testId, LocalDate start, LocalDate end) {
        return oracle.snapshot().results().stream()
                .filter(r -> r.testId().equals(testId)
                        && r.periodStart().equals(start) && r.periodEnd().equals(end))
                .findFirst().orElseThrow().status();
    }

    private void testLocalWaiverKeepsEvidence() {
        LocalDate q1Start = LocalDate.of(2025, 1, 1);
        LocalDate q1End = LocalDate.of(2025, 4, 1);
        LocalDate q2Start = q1End;
        LocalDate q2End = LocalDate.of(2025, 7, 1);
        MetricSpec ebitda = quarterly("ebitda", USD);
        MetricCatalog catalog = new MetricCatalog(List.of(ebitda));
        CovenantTest minimum = new CovenantTest("t_min", TestKind.MINIMUM,
                "ebitda", null, null, new BigDecimal("50.00"), USD, 0, null, true);
        FactStore store = new FactStore();
        store.add(fact("ebitda", q1Start, q1End, "10.00", "v1", USD), USD);
        store.add(fact("ebitda", q2Start, q2End, "10.00", "v1", USD), USD);
        // Waiver covers exactly Q1 only.
        covenant.Waiver waiver = new covenant.Waiver("w1", BORROWER, "t_min",
                q1Start, q1End, q1End, q1End.plusDays(30));
        Evaluator evaluator = new Evaluator(store, catalog, List.of(minimum),
                List.of(waiver), List.of());
        TestResult q1 = evaluator.evaluateAt(BORROWER, minimum, q1Start, q1End,
                q1End.plusDays(5));
        TestResult q2 = evaluator.evaluateAt(BORROWER, minimum, q2Start, q2End,
                q2End.plusDays(5));
        check(q1.status() == Status.WAIVED, "Q1 breach is waived");
        check(!q1.breachEvidence().isEmpty(), "waived Q1 keeps original breach evidence");
        check(q2.status() == Status.BREACHED, "Q1 waiver does not cover Q2");

        // Waiver scoped to a different test never applies.
        covenant.Waiver otherTest = new covenant.Waiver("w2", BORROWER, "other",
                q1Start, q1End, q1End, q1End.plusDays(30));
        Evaluator evaluator2 = new Evaluator(store, catalog, List.of(minimum),
                List.of(otherTest), List.of());
        check(evaluator2.evaluateAt(BORROWER, minimum, q1Start, q1End, q1End.plusDays(5))
                .status() == Status.BREACHED, "waiver for other test does not apply");

        // Expired waiver no longer waives, evidence remains.
        covenant.Waiver expired = new covenant.Waiver("w3", BORROWER, "t_min",
                q1Start, q1End, q1End, q1End.plusDays(10));
        Evaluator evaluator3 = new Evaluator(store, catalog, List.of(minimum),
                List.of(expired), List.of());
        TestResult afterExpiry = evaluator3.evaluateAt(BORROWER, minimum,
                q1Start, q1End, q1End.plusDays(11));
        check(afterExpiry.status() == Status.BREACHED, "expired waiver does not waive");
        check(!afterExpiry.breachEvidence().isEmpty(),
                "expired waiver still leaves breach evidence");

        // Oracle level: waiver expiry while still inside grace returns to CURE_PENDING,
        // and default happens only when the grace window also ends.
        FactStore singlePeriodStore = new FactStore();
        singlePeriodStore.add(fact("ebitda", q1Start, q1End, "10.00", "v1", USD), USD);
        CovenantTest minimumWithGrace = new CovenantTest("t_min_grace", TestKind.MINIMUM,
                "ebitda", null, null, new BigDecimal("50.00"), USD, 10, null, true);
        covenant.Waiver graceScopedWaiver = new covenant.Waiver("w4", BORROWER,
                "t_min_grace", q1Start, q1End, q1End, q1End.plusDays(3));
        Evaluator evaluator4 = new Evaluator(singlePeriodStore, catalog,
                List.of(minimumWithGrace),
                List.of(graceScopedWaiver), List.of());
        SimulationClock clock4 = new SimulationClock(q1End);
        Oracle oracle4 = new Oracle(evaluator4, new covenant.StateMachine(), clock4);
        oracle4.registerBorrowerPeriods(BORROWER, q1Start, 3, 1);
        oracle4.advanceTo(q1End.plusDays(2));
        check(statusAt(oracle4, "t_min_grace", q1Start, q1End) == Status.WAIVED,
                "waived during waiver window");
        oracle4.advanceTo(q1End.plusDays(5));
        check(statusAt(oracle4, "t_min_grace", q1Start, q1End) == Status.CURE_PENDING,
                "back to cure_pending after waiver expiry while grace remains");
        oracle4.advanceTo(q1End.plusDays(11));
        check(statusAt(oracle4, "t_min_grace", q1Start, q1End) == Status.DEFAULTED,
                "default once grace ends after waiver expiry");
    }

    private void testPaymentDateTest() {
        LocalDate scheduled = LocalDate.of(2025, 3, 31);
        LocalDate periodStart = LocalDate.of(2025, 1, 1);
        LocalDate periodEnd = LocalDate.of(2025, 4, 1);
        CovenantTest onTimeTest = new CovenantTest("pay", TestKind.PAYMENT_DATE,
                null, null, null, BigDecimal.ZERO, Unit.day(), 5, "P-1", true);
        PaymentEvent onTime = new PaymentEvent(BORROWER, "P-1", scheduled,
                scheduled, new BigDecimal("100.00"), USD);
        PaymentEvent late = new PaymentEvent(BORROWER, "P-1", scheduled,
                scheduled.plusDays(3), new BigDecimal("100.00"), USD);
        PaymentEvent unpaid = new PaymentEvent(BORROWER, "P-1", scheduled,
                null, new BigDecimal("100.00"), USD);

        Evaluator good = new Evaluator(new FactStore(),
                new MetricCatalog(List.of()), List.of(onTimeTest),
                List.of(), List.of(onTime));
        check(good.evaluateAt(BORROWER, onTimeTest, periodStart, periodEnd, scheduled)
                .status() == Status.COMPLIANT, "payment on time compliant");

        Evaluator lateEval = new Evaluator(new FactStore(),
                new MetricCatalog(List.of()), List.of(onTimeTest),
                List.of(), List.of(late));
        check(lateEval.evaluateAt(BORROWER, onTimeTest, periodStart, periodEnd,
                scheduled.plusDays(3)).status() == Status.BREACHED, "late payment breach");

        Evaluator unpaidEval = new Evaluator(new FactStore(),
                new MetricCatalog(List.of()), List.of(onTimeTest),
                List.of(), List.of(unpaid));
        check(unpaidEval.evaluateAt(BORROWER, onTimeTest, periodStart, periodEnd,
                scheduled.plusDays(1)).status() == Status.BREACHED,
                "unpaid after scheduled date breach");

        Evaluator noPayments = new Evaluator(new FactStore(),
                new MetricCatalog(List.of()), List.of(onTimeTest), List.of(), List.of());
        check(noPayments.evaluateAt(BORROWER, onTimeTest, periodStart, periodEnd, scheduled)
                .status() == Status.BREACHED,
                "missing payment event with breachIfMissing becomes breach");

        CovenantTest lenient = new CovenantTest("pay_lenient", TestKind.PAYMENT_DATE,
                null, null, null, BigDecimal.ZERO, Unit.day(), 5, "P-UNKNOWN", false);
        Evaluator lenientEval = new Evaluator(new FactStore(),
                new MetricCatalog(List.of()), List.of(lenient), List.of(), List.of());
        check(lenientEval.evaluateAt(BORROWER, lenient, periodStart, periodEnd, scheduled)
                .status() == Status.COMPLIANT, "missing payment ignored when breachIfMissing=false");

        // Oracle level: grace for a payment breach starts on the scheduled date,
        // even though the enclosing test period ends later.
        CovenantTest payWithGrace = new CovenantTest("pay_grace", TestKind.PAYMENT_DATE,
                null, null, null, BigDecimal.ZERO, Unit.day(), 5, "P-2", true);
        PaymentEvent unpaid2 = new PaymentEvent(BORROWER, "P-2", scheduled,
                null, new BigDecimal("50.00"), USD);
        Evaluator payEval = new Evaluator(new FactStore(),
                new MetricCatalog(List.of()), List.of(payWithGrace), List.of(),
                List.of(unpaid2));
        SimulationClock payClock = new SimulationClock(periodStart);
        Oracle payOracle = new Oracle(payEval, new covenant.StateMachine(), payClock);
        payOracle.registerBorrowerPeriods(BORROWER, periodStart, 3, 1);
        payOracle.advanceTo(scheduled.plusDays(5));
        check(statusAt(payOracle, "pay_grace", periodStart, periodEnd) == Status.CURE_PENDING,
                "payment breach within grace counted from scheduled date");
        payOracle.advanceTo(scheduled.plusDays(6));
        check(statusAt(payOracle, "pay_grace", periodStart, periodEnd) == Status.DEFAULTED,
                "payment default one day after payment grace");
    }

    private void testRestatement() {
        LocalDate q1Start = LocalDate.of(2025, 1, 1);
        LocalDate q1End = LocalDate.of(2025, 4, 1);
        LocalDate q2Start = q1End;
        LocalDate q2End = LocalDate.of(2025, 7, 1);
        MetricSpec ebitda = quarterly("ebitda", USD);
        MetricCatalog catalog = new MetricCatalog(List.of(ebitda));
        CovenantTest minimum = new CovenantTest("t_min", TestKind.MINIMUM,
                "ebitda", null, null, new BigDecimal("50.00"), USD, 0, null, true);
        FactStore published = new FactStore();
        published.add(fact("ebitda", q1Start, q1End, "60.00", "v1", USD), USD);
        published.add(fact("ebitda", q2Start, q2End, "60.00", "v1", USD), USD);
        Evaluator evaluator = new Evaluator(published, catalog, List.of(minimum),
                List.of(), List.of());
        Engine engine = new Engine(evaluator);
        List<Engine.PeriodKey> periods = List.of(
                new Engine.PeriodKey(BORROWER, q1Start, q1End),
                new Engine.PeriodKey(BORROWER, q2Start, q2End));
        LocalDate asOf = q2End;
        Snapshot before = engine.rebuildAll(asOf, periods);
        check(before.results().stream().allMatch(r -> r.status() == Status.COMPLIANT),
                "published snapshot compliant before restatement");

        // Restate Q1 downward: earliest affected period is Q1.
        FactStore candidate = new FactStore();
        candidate.add(fact("ebitda", q1Start, q1End, "60.00", "v1", USD), USD);
        candidate.add(fact("ebitda", q1Start, q1End, "10.00", "v2", USD), USD);
        candidate.add(fact("ebitda", q2Start, q2End, "60.00", "v1", USD), USD);
        covenant.RestatementResult result = engine.restate(published, candidate,
                before, asOf, periods, BORROWER);
        check(result.applied(), "valid restatement applies");
        check(result.byteIdentical(), "repeated full rebuild byte-identical");
        check(result.incrementalAlerts().size() == 1,
                "incremental alert only for earliest affected period");
        check(result.incrementalAlerts().get(0).periodLabel()
                        .equals(Periods.label(q1Start, q1End)),
                "incremental alert anchored at Q1");
        check(result.rebuiltSnapshot().results().stream()
                        .filter(r -> r.periodStart().equals(q1Start))
                        .findFirst().orElseThrow().status() == Status.BREACHED,
                "rebuilt Q1 shows breach");

        // Published snapshot object itself remains valid until callers swap it.
        check(before.digest().equals(engine.rebuildAll(asOf, periods).digest()),
                "published results unchanged after rejected/failed restatement baseline");

        // Failed restatement (overlapping invalid input would throw on add; instead
        // use an empty-candidate evaluator producing missing-fact breach is still
        // a valid rebuild, so we simulate failure by dividing metric referencing
        // undefined metric via a poisoned catalog is out of scope; here we assert
        // that a structurally failing candidate (unit conflict) never replaces.
        FactStore poisoned = new FactStore();
        boolean poisonCaught = false;
        try {
            poisoned.add(fact("ebitda", q1Start, q1End, "60.00", "v1", USD), USD);
            poisoned.add(fact("ebitda", q1Start, q1End, "10.00", "v1",
                    Unit.money("EUR")), USD);
        } catch (UnitConflictException e) {
            poisonCaught = true;
        }
        check(poisonCaught, "invalid candidate rejected at ingestion");

        // Failing restatement: Q1 denominator becomes zero -> division by zero
        // during rebuild; published snapshot must be returned untouched.
        MetricSpec ratioNum = quarterly("ebitda", USD);
        MetricSpec ratioDen = quarterly("debtService", USD);
        MetricSpec dscr = new MetricSpec("dscr", RATIO, PeriodBasis.CUMULATIVE,
                Annualization.NONE, RoundingOrder.ROUND_AFTER_ANNUALIZE, 4,
                new covenant.Expr.BinaryOp(Operator.DIVIDE,
                        new covenant.Expr.FactRef("ebitda"),
                        new covenant.Expr.FactRef("debtService")));
        MetricCatalog ratioCatalog = new MetricCatalog(List.of(ratioNum, ratioDen, dscr));
        CovenantTest ratioTest = new CovenantTest("t_dscr", TestKind.RATIO, null,
                "ebitda", "debtService", new BigDecimal("1.25"), RATIO, 0,
                null, true);
        FactStore ratioPublished = new FactStore();
        ratioPublished.add(fact("ebitda", q1Start, q1End, "100.00", "v1", USD), USD);
        ratioPublished.add(fact("debtService", q1Start, q1End, "80.00", "v1", USD), USD);
        Evaluator ratioEvaluator = new Evaluator(ratioPublished, ratioCatalog,
                List.of(ratioTest), List.of(), List.of());
        Engine ratioEngine = new Engine(ratioEvaluator);
        Snapshot ratioBefore = ratioEngine.rebuildAll(asOf,
                List.of(new Engine.PeriodKey(BORROWER, q1Start, q1End)));
        FactStore ratioCandidate = new FactStore();
        ratioCandidate.add(fact("ebitda", q1Start, q1End, "100.00", "v1", USD), USD);
        ratioCandidate.add(fact("debtService", q1Start, q1End, "80.00", "v1", USD), USD);
        ratioCandidate.add(fact("debtService", q1Start, q1End, "0.00", "v2", USD), USD);
        RestatementResult failed = ratioEngine.restate(ratioPublished, ratioCandidate,
                ratioBefore, asOf,
                List.of(new Engine.PeriodKey(BORROWER, q1Start, q1End)), BORROWER);
        check(!failed.applied(), "failing restatement is not applied");
        check(!failed.byteIdentical(), "failing restatement reports non-identity");
        check(failed.rebuiltSnapshot() == ratioBefore,
                "failing restatement keeps the published snapshot instance");
    }

    private void testOrderIndependence() {
        LocalDate q1Start = LocalDate.of(2025, 1, 1);
        LocalDate q1End = LocalDate.of(2025, 4, 1);
        LocalDate q2Start = q1End;
        LocalDate q2End = LocalDate.of(2025, 7, 1);
        MetricSpec ebitda = quarterly("ebitda", USD);
        MetricSpec debt = quarterly("debt", USD);
        MetricCatalog catalog = new MetricCatalog(List.of(ebitda, debt));
        CovenantTest minEbitda = new CovenantTest("t_ebitda", TestKind.MINIMUM,
                "ebitda", null, null, new BigDecimal("50.00"), USD, 5, null, true);
        CovenantTest maxDebt = new CovenantTest("t_debt", TestKind.MAXIMUM,
                "debt", null, null, new BigDecimal("500.00"), USD, 0, null, true);

        Snapshot first = buildSnapshot(catalog,
                List.of(fact("ebitda", q1Start, q1End, "60.00", "v1", USD),
                        fact("debt", q1Start, q1End, "400.00", "v1", USD),
                        fact("ebitda", q2Start, q2End, "40.00", "v1", USD),
                        fact("debt", q2Start, q2End, "600.00", "v1", USD)),
                List.of(minEbitda, maxDebt), q2End);
        Snapshot shuffled = buildSnapshot(catalog,
                List.of(fact("debt", q2Start, q2End, "600.00", "v1", USD),
                        fact("ebitda", q2Start, q2End, "40.00", "v1", USD),
                        fact("debt", q1Start, q1End, "400.00", "v1", USD),
                        fact("ebitda", q1Start, q1End, "60.00", "v1", USD)),
                List.of(maxDebt, minEbitda), q2End);
        check(first.digest().equals(shuffled.digest()),
                "canonical snapshot independent of input order");

        // Rebuild twice: byte-for-byte identical.
        Snapshot again = buildSnapshot(catalog,
                List.of(fact("ebitda", q1Start, q1End, "60.00", "v1", USD),
                        fact("debt", q1Start, q1End, "400.00", "v1", USD),
                        fact("ebitda", q2Start, q2End, "40.00", "v1", USD),
                        fact("debt", q2Start, q2End, "600.00", "v1", USD)),
                List.of(minEbitda, maxDebt), q2End);
        check(java.util.Arrays.equals(first.canonicalBytes(), again.canonicalBytes()),
                "repeated rebuild byte-identical");
    }

    private Snapshot buildSnapshot(MetricCatalog catalog, List<Fact> facts,
                                   List<CovenantTest> tests, LocalDate asOf) {
        FactStore store = new FactStore();
        for (Fact factItem : facts) {
            store.add(factItem, USD);
        }
        Evaluator evaluator = new Evaluator(store, catalog, tests, List.of(), List.of());
        Engine engine = new Engine(evaluator);
        LocalDate q1Start = LocalDate.of(2025, 1, 1);
        LocalDate q1End = LocalDate.of(2025, 4, 1);
        LocalDate q2Start = q1End;
        LocalDate q2End = LocalDate.of(2025, 7, 1);
        return engine.rebuildAll(asOf, List.of(
                new Engine.PeriodKey(BORROWER, q2Start, q2End),
                new Engine.PeriodKey(BORROWER, q1Start, q1End)));
    }

    private void report() {
        System.out.println("checks=" + checks + " failures=" + failures.size());
        for (String failure : failures) {
            System.out.println("FAIL: " + failure);
        }
    }
}
