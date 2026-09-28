package covenant.cli;

import covenant.Alert;
import covenant.Annualization;
import covenant.CovenantTest;
import covenant.Engine;
import covenant.Evaluator;
import covenant.Fact;
import covenant.FactStore;
import covenant.MetricCatalog;
import covenant.MetricSpec;
import covenant.Operator;
import covenant.Oracle;
import covenant.PaymentEvent;
import covenant.PeriodBasis;
import covenant.RoundingOrder;
import covenant.SimulationClock;
import covenant.Status;
import covenant.TestKind;
import covenant.Unit;
import covenant.Waiver;
import covenant.Expr;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class Main {

    private static final String BORROWER = "HARBOR LIGHT FICTIONAL CO";
    private static final Unit USD = Unit.money("USD");
    private static final Unit RATIO = Unit.ratio();

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println("Usage: java -cp out covenant.cli.Main [--selftest]");
            System.out.println("With no arguments, runs the built-in fictional demo.");
            return;
        }
        if (args.length == 1 && args[0].equals("--selftest")) {
            System.exit(covenant.tests.SelfTest.run() ? 0 : 1);
        }
        LocalDate q1Start = LocalDate.of(2025, 1, 1);
        LocalDate q1End = LocalDate.of(2025, 4, 1);
        LocalDate q2Start = q1End;
        LocalDate q2End = LocalDate.of(2025, 7, 1);

        MetricSpec ebitda = new MetricSpec("ebitda", USD, PeriodBasis.CUMULATIVE,
                Annualization.NONE, RoundingOrder.ROUND_AFTER_ANNUALIZE, 2,
                new Expr.FactRef("ebitda"));
        MetricSpec debtService = new MetricSpec("debtService", USD, PeriodBasis.CUMULATIVE,
                Annualization.NONE, RoundingOrder.ROUND_AFTER_ANNUALIZE, 2,
                new Expr.FactRef("debtService"));
        MetricSpec dscr = new MetricSpec("dscr", RATIO, PeriodBasis.CUMULATIVE,
                Annualization.NONE, RoundingOrder.ROUND_AFTER_ANNUALIZE, 4,
                new Expr.BinaryOp(Operator.DIVIDE,
                        new Expr.FactRef("ebitda"),
                        new Expr.FactRef("debtService")));
        MetricCatalog catalog = new MetricCatalog(List.of(ebitda, debtService, dscr));

        FactStore store = new FactStore();
        store.add(new Fact(BORROWER, "ebitda", USD, q1Start, q1End,
                new BigDecimal("100.00"), "v1", q1End, "ledger:q1"), USD);
        store.add(new Fact(BORROWER, "debtService", USD, q1Start, q1End,
                new BigDecimal("80.00"), "v1", q1End, "ledger:q1"), USD);
        // Q2 is restated downward (fictional) to demonstrate cure/default transition.
        store.add(new Fact(BORROWER, "ebitda", USD, q2Start, q2End,
                new BigDecimal("60.00"), "v1", q2End, "ledger:q2"), USD);
        store.add(new Fact(BORROWER, "debtService", USD, q2Start, q2End,
                new BigDecimal("100.00"), "v1", q2End, "ledger:q2"), USD);

        CovenantTest dscrTest = new CovenantTest("COV-DSCR", TestKind.RATIO, null,
                "ebitda", "debtService", new BigDecimal("1.2000"), RATIO, 10,
                null, true);
        PaymentEvent payment = new PaymentEvent(BORROWER, "P-Q1",
                LocalDate.of(2025, 3, 31), LocalDate.of(2025, 3, 31),
                new BigDecimal("80.00"), USD);
        Waiver q2Waiver = new Waiver("W-Q2", BORROWER, "COV-DSCR",
                q2Start, q2End, q2End, q2End.plusDays(7));

        Evaluator evaluator = new Evaluator(store, catalog, List.of(dscrTest),
                List.of(q2Waiver), List.of(payment));
        Oracle oracle = new Oracle(evaluator, new covenant.StateMachine(),
                new SimulationClock(q1End));
        oracle.registerBorrowerPeriods(BORROWER, q1Start, 3, 2);
        oracle.advanceTo(q2End.plusDays(12));

        System.out.println("== LOAN COVENANT MONITOR DEMO (all entities and data fictional) ==");
        System.out.println(oracle.snapshot().toCanonicalText());
        System.out.println("snapshot-digest=" + oracle.snapshot().digest());
    }
}
