package fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** Forward outright points/rate computation satisfying covered interest parity. */
public final class ForwardCalculator {

    public ForwardCalculator() {
    }

    public BigDecimal forwardRate(Pair pair, LocalDate deliveryDate, MarketSnapshot snapshot) {
        Objects.requireNonNull(pair, "pair");
        Objects.requireNonNull(deliveryDate, "deliveryDate");
        Objects.requireNonNull(snapshot, "snapshot");
        BigDecimal spot = snapshot.spot(pair);
        BigDecimal dfBase = snapshot.curve(pair.base()).rawDiscountFactor(deliveryDate);
        BigDecimal dfCounter = snapshot.curve(pair.counter()).rawDiscountFactor(deliveryDate);
        if (dfBase.signum() <= 0 || dfCounter.signum() <= 0) {
            throw new FxException(Failure.NEGATIVE_DISCOUNT_FACTOR,
                    "non-positive discount factor at " + deliveryDate);
        }
        // Covered interest parity: F = S * DF_base / DF_counter.
        BigDecimal forward = Scales.div(Scales.mul(spot, dfBase), dfCounter);
        if (forward.signum() <= 0) {
            throw new FxException(Failure.ARITHMETIC_OVERFLOW,
                    "computed forward rate is not positive: " + forward);
        }
        return Scales.checked(forward);
    }
}
