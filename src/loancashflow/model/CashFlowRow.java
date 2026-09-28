package loancashflow.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One scheduled period. Accrual boundaries ({@code accrualStart}/{@code accrualEnd}) are
 * never altered by business-day adjustment of {@code paymentDate}.
 */
public record CashFlowRow(
        int ordinal,
        LocalDate accrualStart,
        LocalDate accrualEnd,
        LocalDate paymentDate,
        BigDecimal openingBalance,
        BigDecimal advance,
        BigDecimal interestRate,
        BigDecimal yearFraction,
        BigDecimal interest,
        BigDecimal scheduledPrincipal,
        BigDecimal prepayment,
        BigDecimal totalPrincipal,
        BigDecimal payment,
        BigDecimal closingBalance,
        String resetDate) {
}
