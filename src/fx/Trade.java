package fx;

import java.math.BigDecimal;

/** An FX forward trade. Notional is denominated in the base currency. */
public record Trade(
        String id,
        Pair pair,
        Side side,
        BigDecimal baseNotional,
        Schedule schedule,
        BigDecimal contractedForward) {

    public Trade {
        if (id == null || id.isBlank()) {
            throw new FxException(Failure.FIXTURE_ERROR, "trade id must not be blank");
        }
        if (pair == null || side == null || schedule == null) {
            throw new FxException(Failure.FIXTURE_ERROR, "trade " + id + " has null fields");
        }
        if (baseNotional == null || baseNotional.signum() <= 0) {
            throw new FxException(Failure.ARITHMETIC_OVERFLOW,
                    "trade " + id + " notional must be positive: " + baseNotional);
        }
        if (contractedForward == null || contractedForward.signum() <= 0) {
            throw new FxException(Failure.ARITHMETIC_OVERFLOW,
                    "trade " + id + " contracted forward must be positive: " + contractedForward);
        }
        Scales.checked(baseNotional);
        Scales.checked(contractedForward);
        baseNotional = Scales.money(baseNotional);
        contractedForward = Scales.rate(contractedForward);
    }
}
