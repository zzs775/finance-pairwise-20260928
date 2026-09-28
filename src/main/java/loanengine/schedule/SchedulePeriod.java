package loanengine.schedule;

import java.time.LocalDate;

public record SchedulePeriod(
        int period,
        LocalDate accrualStart,
        LocalDate accrualEnd,
        LocalDate unadjustedPaymentDate,
        LocalDate paymentDate,
        boolean stub,
        LocalDate resetDate) {
}
