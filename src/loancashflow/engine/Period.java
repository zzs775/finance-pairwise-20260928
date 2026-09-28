package loancashflow.engine;

import java.time.LocalDate;

/**
 * One accrual period. Payment dates may be business-day adjusted; the adjusted date is
 * never written back into the accrual boundaries.
 */
public record Period(int ordinal, LocalDate accrualStart, LocalDate accrualEnd,
                     LocalDate unadjustedPaymentDate, LocalDate paymentDate) {
}
