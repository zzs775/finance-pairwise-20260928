package loanengine;

import loanengine.dates.DayCount;
import loanengine.dates.Frequency;
import loanengine.engine.CashflowEngine;
import loanengine.engine.CashflowRow;
import loanengine.model.Contract;
import loanengine.model.Prepayment;
import loanengine.model.Prepayment.RecastMode;
import loanengine.rate.ResetSpec;
import loanengine.schedule.AmortizationType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static loanengine.TestFramework.check;
import static loanengine.TestFramework.assertEquals;

public final class PrepaymentTest {

    private PrepaymentTest() {
    }

    public static void run() {
        shortenTermEqualPrincipal();
        keepTermRecomputesEqualPayment();
        frozenPeriodPosition();
        noNegativeBalance();
        prepaymentOnMaturity();
    }

    private static Contract base(List<Prepayment> prepayments, AmortizationType type) {
        return Contract.builder()
                .notional(new BigDecimal("12000.00"))
                .valueDate(LocalDate.of(2025, 1, 31))
                .maturityDate(LocalDate.of(2025, 7, 31))
                .amortizationType(type)
                .frequency(Frequency.MONTHLY)
                .dayCount(DayCount.ACT_360)
                .reset(ResetSpec.fixed(new BigDecimal("0.12")))
                .prepayments(prepayments)
                .build();
    }

    private static void assertAllRepaid(List<CashflowRow> rows, BigDecimal notional) {
        BigDecimal principal = BigDecimal.ZERO;
        for (CashflowRow row : rows) {
            BigDecimal recomputed = row.openingBalance().add(row.advance())
                    .subtract(row.scheduledPrincipal()).subtract(row.prepayment());
            assertEquals(0, recomputed.compareTo(row.closingBalance()),
                    "period " + row.period() + " balance identity");
            check(row.closingBalance().signum() >= 0,
                    "period " + row.period() + " closing non-negative");
            principal = principal.add(row.scheduledPrincipal()).add(row.prepayment());
        }
        assertEquals(0, notional.compareTo(principal), "principal sums to notional");
        assertEquals(0, rows.get(rows.size() - 1).closingBalance().compareTo(BigDecimal.ZERO),
                "last closing zero");
    }

    private static void shortenTermEqualPrincipal() {
        Prepayment prepay = new Prepayment(LocalDate.of(2025, 3, 15),
                new BigDecimal("3000.00"), RecastMode.SHORTEN_TERM);
        List<CashflowRow> rows = new CashflowEngine().generate(
                base(List.of(prepay), AmortizationType.EQUAL_PRINCIPAL));
        assertAllRepaid(rows, new BigDecimal("12000.00"));
        CashflowRow period2 = rows.get(1);
        assertEquals(0, new BigDecimal("3000.00").compareTo(period2.prepayment()),
                "prepayment booked in frozen period 2");
        check(period2.recast(), "period 2 flagged as recast");
        check(rows.size() < 6, "shorten term ends earlier than original schedule, was "
                + rows.size());
    }

    private static void keepTermRecomputesEqualPayment() {
        Prepayment prepay = new Prepayment(LocalDate.of(2025, 3, 15),
                new BigDecimal("2000.00"), RecastMode.KEEP_TERM);
        List<CashflowRow> without = new CashflowEngine().generate(
                base(List.of(), AmortizationType.EQUAL_PAYMENT));
        List<CashflowRow> rows = new CashflowEngine().generate(
                base(List.of(prepay), AmortizationType.EQUAL_PAYMENT));
        assertAllRepaid(rows, new BigDecimal("12000.00"));
        assertEquals(6, rows.size(), "keep term preserves original period count");

        BigDecimal beforeRecast = rows.get(0).totalPayment();
        BigDecimal afterRecast = rows.get(3).totalPayment();
        check(afterRecast.compareTo(beforeRecast) < 0,
                "payment after keep-term recast is lower: " + beforeRecast
                        + " -> " + afterRecast);
        assertEquals(0, without.get(0).totalPayment().compareTo(rows.get(0).totalPayment()),
                "payments before recast unchanged");
    }

    private static void frozenPeriodPosition() {
        Prepayment prepay = new Prepayment(LocalDate.of(2025, 3, 31),
                new BigDecimal("500.00"), RecastMode.SHORTEN_TERM);
        List<CashflowRow> rows = new CashflowEngine().generate(
                base(List.of(prepay), AmortizationType.EQUAL_PRINCIPAL));
        assertEquals(0, new BigDecimal("500.00").compareTo(rows.get(2).prepayment()),
                "prepayment on accrual boundary 3/31 lands in period 3 (frozen order)");
        assertEquals(0, rows.get(1).prepayment().compareTo(BigDecimal.ZERO),
                "period 2 has no prepayment");
    }

    private static void noNegativeBalance() {
        Prepayment oversized = new Prepayment(LocalDate.of(2025, 2, 15),
                new BigDecimal("999999.00"), RecastMode.SHORTEN_TERM);
        List<CashflowRow> rows = new CashflowEngine().generate(
                base(List.of(oversized), AmortizationType.EQUAL_PRINCIPAL));
        for (CashflowRow row : rows) {
            check(row.closingBalance().signum() >= 0,
                    "oversized prepayment never creates negative balance");
        }
        assertEquals(0, rows.get(rows.size() - 1).closingBalance().compareTo(BigDecimal.ZERO),
                "loan fully repaid after oversized prepayment");
    }

    private static void prepaymentOnMaturity() {
        Prepayment onMaturity = new Prepayment(LocalDate.of(2025, 7, 31),
                new BigDecimal("12000.00"), RecastMode.SHORTEN_TERM);
        List<CashflowRow> rows = new CashflowEngine().generate(
                base(List.of(onMaturity), AmortizationType.BULLET));
        CashflowRow last = rows.get(rows.size() - 1);
        assertEquals(0, new BigDecimal("12000.00").compareTo(last.prepayment()),
                "prepayment on maturity booked in final period");
        assertEquals(0, last.scheduledPrincipal().compareTo(BigDecimal.ZERO),
                "prepayment satisfies repayment instead of scheduled principal");
        assertEquals(0, last.closingBalance().compareTo(BigDecimal.ZERO),
                "final period still closes at zero");
    }
}
