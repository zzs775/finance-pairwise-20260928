package fi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record AccrualPeriod(
        int index,
        LocalDate unadjustedStart,
        LocalDate unadjustedEnd,
        LocalDate paymentDate,
        BigDecimal notional,
        BigDecimal annualRate,
        BigDecimal yearFraction,
        BigDecimal couponAmount,
        List<AccrualSegment> segments
) {
    public AccrualPeriod {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }
}
