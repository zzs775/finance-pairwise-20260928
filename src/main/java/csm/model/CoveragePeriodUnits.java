package csm.model;

import java.math.BigDecimal;

/**
 * 某期间的覆盖单位：当期提供单位与剩余总覆盖单位（含当期）。
 */
public record CoveragePeriodUnits(
        int periodIndex,
        BigDecimal currentPeriodUnits,
        BigDecimal totalRemainingUnits) {
}
