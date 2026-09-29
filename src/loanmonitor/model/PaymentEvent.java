package loanmonitor.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A scheduled payment. {@code paidDate} is null while payment is outstanding.
 */
public record PaymentEvent(
        String id,
        String borrower,
        String scheduleRef,
        BigDecimal amount,
        Unit unit,
        LocalDate dueDate,
        LocalDate paidDate) {
}
