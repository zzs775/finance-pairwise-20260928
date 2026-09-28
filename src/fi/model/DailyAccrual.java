package fi.model;

import fi.BondTerms;
import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyAccrual(
        BondTerms terms,
        LocalDate settlementDate,
        int periodIndex,
        BigDecimal accruedAmount,
        BigDecimal nextCouponAmount,
        boolean nextCouponPayableToBuyer
) {
}
