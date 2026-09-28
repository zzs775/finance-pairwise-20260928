package loancashflow.model;

import java.math.BigDecimal;

/**
 * Rate specification. A fixed contract carries a non-null fixedRate.
 * A floating contract carries reset rules; the fixing table lives on the contract.
 */
public record RateSpec(
        boolean floating,
        BigDecimal fixedRate,
        BigDecimal spread,
        BigDecimal floor,
        BigDecimal cap,
        ResetSpec reset) {

    public static RateSpec fixed(BigDecimal rate) {
        return new RateSpec(false,
                loancashflow.engine.Money.rate(rate), null, null, null, null);
    }

    public static RateSpec floating(BigDecimal spread, BigDecimal floor, BigDecimal cap,
                                    ResetSpec reset) {
        return new RateSpec(true, null,
                loancashflow.engine.Money.rate(spread),
                floor == null ? null : loancashflow.engine.Money.rate(floor),
                cap == null ? null : loancashflow.engine.Money.rate(cap),
                reset);
    }
}
