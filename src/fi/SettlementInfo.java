package fi;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SettlementInfo(
        LocalDate settlementDate,
        int currentPeriodIndex,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal accruedAmount,
        boolean nextCouponPayableToBuyer
) {
}
