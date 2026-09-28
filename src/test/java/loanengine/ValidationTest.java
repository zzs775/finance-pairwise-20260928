package loanengine;

import loanengine.core.LoanException;
import loanengine.dates.DayCount;
import loanengine.dates.Frequency;
import loanengine.model.Contract;
import loanengine.rate.ResetSpec;
import loanengine.schedule.AmortizationType;

import java.math.BigDecimal;
import java.time.LocalDate;

import static loanengine.TestFramework.assertEquals;
import static loanengine.TestFramework.check;

public final class ValidationTest {

    private ValidationTest() {
    }

    public static void run() {
        negativeNotional();
        maturityNotAfterValue();
        scaleOverflow();
        invalidPrepaymentDate();
        negativePrepayment();
    }

    private static Contract.Builder validBuilder() {
        return Contract.builder()
                .notional(new BigDecimal("1000.00"))
                .valueDate(LocalDate.of(2025, 1, 31))
                .maturityDate(LocalDate.of(2026, 1, 31))
                .amortizationType(AmortizationType.BULLET)
                .frequency(Frequency.QUARTERLY)
                .dayCount(DayCount.ACT_360)
                .reset(ResetSpec.fixed(new BigDecimal("0.05")));
    }

    private static void expectCategory(LoanException.Category expected, Runnable runnable,
                                       String label) {
        try {
            runnable.run();
            check(false, label + " should throw");
        } catch (LoanException e) {
            assertEquals(expected, e.category(), label + " category");
        }
    }

    private static void negativeNotional() {
        expectCategory(LoanException.Category.NEGATIVE_PRINCIPAL,
                () -> validBuilder().notional(new BigDecimal("-5.00")).build(),
                "negative notional");
        expectCategory(LoanException.Category.NEGATIVE_PRINCIPAL,
                () -> validBuilder().notional(BigDecimal.ZERO).build(),
                "zero notional");
    }

    private static void maturityNotAfterValue() {
        expectCategory(LoanException.Category.INVALID_DATE,
                () -> validBuilder().maturityDate(LocalDate.of(2025, 1, 31)).build(),
                "maturity equal value");
        expectCategory(LoanException.Category.INVALID_DATE,
                () -> validBuilder().firstPaymentDate(LocalDate.of(2024, 12, 31)).build(),
                "first payment before value");
    }

    private static void scaleOverflow() {
        expectCategory(LoanException.Category.SCALE_OVERFLOW,
                () -> validBuilder().notional(new BigDecimal("1000.001")).build(),
                "fractional-cent notional");
    }

    private static void invalidPrepaymentDate() {
        expectCategory(LoanException.Category.INVALID_DATE,
                () -> validBuilder()
                        .prepayments(java.util.List.of(new loanengine.model.Prepayment(
                                LocalDate.of(2026, 2, 1),
                                new BigDecimal("100.00"),
                                loanengine.model.Prepayment.RecastMode.SHORTEN_TERM)))
                        .build(),
                "prepayment after maturity rejected");
    }

    private static void negativePrepayment() {
        expectCategory(LoanException.Category.NEGATIVE_PRINCIPAL,
                () -> validBuilder()
                        .prepayments(java.util.List.of(new loanengine.model.Prepayment(
                                LocalDate.of(2025, 6, 30),
                                new BigDecimal("-1.00"),
                                loanengine.model.Prepayment.RecastMode.SHORTEN_TERM)))
                        .build(),
                "negative prepayment");
    }
}
