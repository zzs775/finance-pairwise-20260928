package fi;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AccrualSegment(
        LocalDate start,
        LocalDate end,
        BigDecimal notional,
        BigDecimal annualRate,
        BigDecimal yearFraction,
        BigDecimal couponAmount
) {
}
