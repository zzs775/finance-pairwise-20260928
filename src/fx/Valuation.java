package fx;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Result of valuing one trade under one snapshot. */
public record Valuation(
        String tradeId,
        String snapshotVersion,
        LocalDate deliveryDate,
        BigDecimal forwardRateDisplay,
        BigDecimal forwardRate,
        BigDecimal npvForwardDifference,
        BigDecimal npvCashFlow,
        BigDecimal residual,
        BigDecimal conservativeResidual,
        BigDecimal npv) {
}
