package loanengine;

import loanengine.core.LoanException;
import loanengine.dates.DayCount;
import loanengine.dates.Frequency;
import loanengine.engine.CashflowEngine;
import loanengine.engine.CashflowRow;
import loanengine.model.Contract;
import loanengine.rate.Fixing;
import loanengine.rate.FixingSeries;
import loanengine.rate.ResetSpec;
import loanengine.schedule.AmortizationType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static loanengine.TestFramework.check;
import static loanengine.TestFramework.assertEquals;

public final class FloatingRateTest {

    private FloatingRateTest() {
    }

    public static void run() {
        spreadFloorAndCap();
        latestVersionSelected();
        missingFixingRaises();
        correctionMatchesFullRegeneration();
        quarterlyActual360();
    }

    private static Contract contractWith(ResetSpec reset) {
        return Contract.builder()
                .notional(new BigDecimal("6000.00"))
                .valueDate(LocalDate.of(2025, 1, 31))
                .maturityDate(LocalDate.of(2025, 7, 31))
                .amortizationType(AmortizationType.EQUAL_PRINCIPAL)
                .frequency(Frequency.MONTHLY)
                .dayCount(DayCount.ACT_365F)
                .reset(reset)
                .build();
    }

    private static void spreadFloorAndCap() {
        ResetSpec reset = new ResetSpec(
                List.of(d(2025, 1, 31), d(2025, 3, 31), d(2025, 5, 31)),
                new FixingSeries(List.of(
                        new Fixing(d(2025, 1, 31), pct("0.040"), 1),
                        new Fixing(d(2025, 3, 31), pct("0.001"), 1),
                        new Fixing(d(2025, 5, 31), pct("0.200"), 1))),
                pct("0.010"), pct("0.020"), pct("0.080"));
        List<CashflowRow> rows = new CashflowEngine().generate(contractWith(reset));
        assertEquals(pct("0.050"), rows.get(0).interestRate(),
                "period 1 rate = fixing 4% + spread 1%");
        assertEquals(pct("0.020"), rows.get(2).interestRate(),
                "rate floored at 2%");
        assertEquals(pct("0.080"), rows.get(4).interestRate(),
                "rate capped at 8%");
    }

    private static void latestVersionSelected() {
        FixingSeries series = new FixingSeries(List.of(
                new Fixing(d(2025, 1, 31), pct("0.040"), 1),
                new Fixing(d(2025, 1, 31), pct("0.045"), 2)));
        ResetSpec reset = new ResetSpec(List.of(d(2025, 1, 31)),
                series, BigDecimal.ZERO, pct("0.000"), pct("1.000"));
        List<CashflowRow> rows = new CashflowEngine().generate(
                contractWith(reset));
        assertEquals(pct("0.045"), rows.get(0).interestRate(),
                "highest fixing version wins");
        assertEquals(2, rows.get(0).fixingVersion(), "fixing version reported");
    }

    private static void missingFixingRaises() {
        ResetSpec reset = new ResetSpec(
                List.of(d(2025, 1, 31), d(2025, 3, 31)),
                new FixingSeries(List.of(
                        new Fixing(d(2025, 1, 31), pct("0.04"), 1))),
                BigDecimal.ZERO, null, null);
        try {
            new CashflowEngine().generate(contractWith(reset));
            check(false, "missing fixing must raise");
        } catch (LoanException e) {
            assertEquals(LoanException.Category.MISSING_FIXING, e.category(),
                    "missing fixing classified as MISSING_FIXING");
        }
    }

    private static void correctionMatchesFullRegeneration() {
        FixingSeries v1 = new FixingSeries(List.of(
                new Fixing(d(2025, 1, 31), pct("0.040"), 1),
                new Fixing(d(2025, 3, 31), pct("0.050"), 1),
                new Fixing(d(2025, 5, 31), pct("0.060"), 1)));
        ResetSpec initial = new ResetSpec(
                List.of(d(2025, 1, 31), d(2025, 3, 31), d(2025, 5, 31)),
                v1, BigDecimal.ZERO, null, null);
        CashflowEngine engine = new CashflowEngine();
        List<CashflowRow> previous = engine.generate(contractWith(initial));

        FixingSeries corrected = new FixingSeries(List.of(
                new Fixing(d(2025, 1, 31), pct("0.040"), 1),
                new Fixing(d(2025, 3, 31), pct("0.075"), 2),
                new Fixing(d(2025, 5, 31), pct("0.060"), 1)));
        ResetSpec correctedSpec = new ResetSpec(
                List.of(d(2025, 1, 31), d(2025, 3, 31), d(2025, 5, 31)),
                corrected, BigDecimal.ZERO, null, null);
        Contract updated = contractWith(correctedSpec);

        List<CashflowRow> incremental =
                engine.applyFixingCorrection(updated, d(2025, 3, 31), previous);
        List<CashflowRow> full = engine.generate(updated);

        assertEquals(full.size(), incremental.size(), "same row count");
        for (int i = 0; i < full.size(); i++) {
            assertRowEquals(full.get(i), incremental.get(i),
                    "period " + (i + 1));
        }
        assertEquals(previous.get(0).interest(), incremental.get(0).interest(),
                "period before corrected reset untouched");
        assertEquals(previous.get(1).interest(), incremental.get(1).interest(),
                "period using prior reset untouched");
    }

    private static void assertRowEquals(CashflowRow expected, CashflowRow actual,
                                        String label) {
        assertEquals(expected.interestRate(), actual.interestRate(),
                label + " rate");
        assertEquals(expected.interest(), actual.interest(), label + " interest");
        assertEquals(expected.scheduledPrincipal(), actual.scheduledPrincipal(),
                label + " principal");
        assertEquals(expected.closingBalance(), actual.closingBalance(),
                label + " closing");
        assertEquals(expected.fixingVersion(), actual.fixingVersion(),
                label + " version");
    }

    private static LocalDate d(int year, int month, int day) {
        return LocalDate.of(year, month, day);
    }

    private static BigDecimal pct(String value) {
        return new BigDecimal(value).setScale(10, java.math.RoundingMode.HALF_UP);
    }

    private static void quarterlyActual360() {
        ResetSpec reset = new ResetSpec(
                List.of(d(2025, 1, 15)),
                new FixingSeries(List.of(
                        new Fixing(d(2025, 1, 15), pct("0.04"), 1))),
                pct("0.01"), null, null);
        Contract contract = Contract.builder()
                .notional(new BigDecimal("9000.00"))
                .valueDate(LocalDate.of(2025, 1, 15))
                .maturityDate(LocalDate.of(2025, 7, 15))
                .amortizationType(AmortizationType.EQUAL_PRINCIPAL)
                .frequency(Frequency.QUARTERLY)
                .dayCount(DayCount.ACT_360)
                .reset(reset)
                .build();
        List<CashflowRow> rows = new CashflowEngine().generate(contract);
        BigDecimal expected = new BigDecimal("9000").multiply(pct("0.05"))
                .multiply(new BigDecimal("90").divide(new BigDecimal("360"),
                        10, java.math.RoundingMode.HALF_UP))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        assertEquals(0, expected.compareTo(rows.get(0).interest()),
                "ACT/360 quarterly interest hand calc");
    }
}
