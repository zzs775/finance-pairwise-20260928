package loanengine.engine;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CashflowRow(
        int period,
        LocalDate accrualStart,
        LocalDate accrualEnd,
        LocalDate paymentDate,
        BigDecimal openingBalance,
        BigDecimal advance,
        BigDecimal interestRate,
        BigDecimal interest,
        BigDecimal scheduledPrincipal,
        BigDecimal prepayment,
        BigDecimal totalPayment,
        BigDecimal closingBalance,
        int fixingVersion,
        boolean recast) {
}
