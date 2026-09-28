package loanengine;

import loanengine.engine.CashflowEngine;
import loanengine.engine.CashflowRow;
import loanengine.model.Contract;
import loanengine.rate.ResetSpec;
import loanengine.schedule.AmortizationType;
import loanengine.dates.DayCount;
import loanengine.dates.Frequency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static loanengine.TestFramework.assertClose;
import static loanengine.TestFramework.assertEquals;
import static loanengine.TestFramework.check;

public final class AmortizationTest {

    private static final BigDecimal CENT = new BigDecimal("0.01");

    private AmortizationTest() {
    }

    public static void run() {
        equalPayment();
        equalPrincipal();
        bullet();
        oddCentTail();
    }

    private static Contract baseContract(AmortizationType type) {
        return Contract.builder()
                .notional(new BigDecimal("10000.00"))
                .valueDate(LocalDate.of(2025, 1, 31))
                .maturityDate(LocalDate.of(2025, 4, 30))
                .amortizationType(type)
                .frequency(Frequency.MONTHLY)
                .dayCount(DayCount.ACT_365F)
                .reset(ResetSpec.fixed(new BigDecimal("0.06")))
                .build();
    }

    private static void assertInvariant(List<CashflowRow> rows, BigDecimal notional) {
        assertEquals(3, rows.size(), "three monthly periods");
        BigDecimal principalSum = BigDecimal.ZERO;
        for (CashflowRow row : rows) {
            BigDecimal recomputed = row.openingBalance().add(row.advance())
                    .subtract(row.scheduledPrincipal()).subtract(row.prepayment());
            assertEquals(0, recomputed.compareTo(row.closingBalance()),
                    "period " + row.period() + " opening+advance-principal-prepay=closing");
            check(row.closingBalance().signum() >= 0,
                    "period " + row.period() + " non-negative closing");
            principalSum = principalSum.add(row.scheduledPrincipal()).add(row.prepayment());
        }
        assertEquals(0, notional.compareTo(principalSum),
                "all principal (plus prepayments) repays notional exactly");
        assertEquals(0, rows.get(rows.size() - 1).closingBalance().compareTo(BigDecimal.ZERO),
                "final closing is zero");
    }

    private static void equalPayment() {
        List<CashflowRow> rows = new CashflowEngine().generate(
                baseContract(AmortizationType.EQUAL_PAYMENT));
        assertInvariant(rows, new BigDecimal("10000.00"));

        BigDecimal i1 = interest("10000", 28);
        BigDecimal balanceAfter1 = new BigDecimal("10000.00")
                .subtract(rows.get(0).scheduledPrincipal());
        BigDecimal i2 = interest(balanceAfter1, 31);
        assertClose(i1, rows.get(0).interest(), CENT, "period 1 interest hand calc");
        assertClose(i2, rows.get(1).interest(), CENT, "period 2 interest hand calc");
        BigDecimal oracleLevel = oracleLevelPayment();
        assertClose(oracleLevel, rows.get(0).totalPayment(), new BigDecimal("0.02"),
                "period 1 total matches hand-solved level payment");
        assertClose(rows.get(0).totalPayment(), rows.get(1).totalPayment(),
                new BigDecimal("0.01"), "period 1 and 2 payments level (cent rounding)");
    }

    private static BigDecimal oracleLevelPayment() {
        BigDecimal periodFactor = new BigDecimal("28")
                .divide(new BigDecimal("365"), 10, java.math.RoundingMode.HALF_UP);
        BigDecimal low = BigDecimal.ZERO;
        BigDecimal high = new BigDecimal("10000");
        for (int iter = 0; iter < 60; iter++) {
            BigDecimal[] balances = new BigDecimal[4];
            balances[0] = new BigDecimal("10000.00");
            BigDecimal payment = low.add(high).divide(new BigDecimal("2"),
                    10, java.math.RoundingMode.HALF_UP);
            for (int p = 0; p < 3; p++) {
                BigDecimal accrued = balances[p].multiply(new BigDecimal("0.06"))
                        .multiply(periodFactor).setScale(2, java.math.RoundingMode.HALF_UP);
                BigDecimal principal = payment.subtract(accrued).min(balances[p]).max(ZERO_BD);
                balances[p + 1] = balances[p].subtract(principal);
            }
            if (balances[3].signum() > 0) {
                low = payment;
            } else {
                high = payment;
            }
        }
        return high.setScale(2, java.math.RoundingMode.CEILING);
    }

    private static final BigDecimal ZERO_BD = BigDecimal.ZERO.setScale(2);

    private static void equalPrincipal() {
        List<CashflowRow> rows = new CashflowEngine().generate(
                baseContract(AmortizationType.EQUAL_PRINCIPAL));
        assertInvariant(rows, new BigDecimal("10000.00"));
        assertEquals(0, new BigDecimal("3333.33")
                .compareTo(rows.get(0).scheduledPrincipal()),
                "equal principal installment rounds to 3333.33");
        assertEquals(0, new BigDecimal("3333.34")
                .compareTo(rows.get(rows.size() - 1).scheduledPrincipal()),
                "final installment absorbs tail cent");
        assertEquals(0, interest("10000", 28).compareTo(rows.get(0).interest()),
                "period 1 interest on full notional");
    }

    private static void bullet() {
        List<CashflowRow> rows = new CashflowEngine().generate(
                baseContract(AmortizationType.BULLET));
        assertEquals(3, rows.size(), "bullet has three periods");
        for (int i = 0; i < 2; i++) {
            assertEquals(0, rows.get(i).scheduledPrincipal().compareTo(BigDecimal.ZERO),
                    "bullet period " + (i + 1) + " repays no principal");
            assertEquals(0, rows.get(i).closingBalance().compareTo(new BigDecimal("10000.00")),
                    "bullet balance stays at notional");
        }
        assertEquals(0, rows.get(2).scheduledPrincipal().compareTo(new BigDecimal("10000.00")),
                "bullet repays all principal at maturity");
        assertEquals(0, rows.get(2).closingBalance().compareTo(BigDecimal.ZERO),
                "bullet final closing zero");
    }

    private static BigDecimal interest(String principal, int days) {
        return interest(new BigDecimal(principal), days);
    }

    private static void oddCentTail() {
        Contract contract = Contract.builder()
                .notional(new BigDecimal("100.01"))
                .valueDate(LocalDate.of(2025, 1, 31))
                .maturityDate(LocalDate.of(2025, 4, 30))
                .amortizationType(AmortizationType.EQUAL_PRINCIPAL)
                .frequency(Frequency.MONTHLY)
                .dayCount(DayCount.ACT_365F)
                .reset(ResetSpec.fixed(new BigDecimal("0.00")))
                .build();
        List<CashflowRow> rows = new CashflowEngine().generate(contract);
        assertEquals(0, new BigDecimal("33.34").compareTo(rows.get(0).scheduledPrincipal()),
                "installment 1 33.34");
        assertEquals(0, new BigDecimal("33.34").compareTo(rows.get(1).scheduledPrincipal()),
                "installment 2 rounds to 33.34");
        assertEquals(0, new BigDecimal("33.33").compareTo(rows.get(2).scheduledPrincipal()),
                "final period absorbs residual cent");
        assertEquals(0, rows.get(2).closingBalance().compareTo(BigDecimal.ZERO),
                "zero closing with odd cent notional");
    }

    private static BigDecimal interest(BigDecimal principal, int days) {
        BigDecimal factor = new BigDecimal(days).divide(new BigDecimal("365"),
                10, java.math.RoundingMode.HALF_UP);
        return principal.multiply(new BigDecimal("0.06")).multiply(factor)
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
