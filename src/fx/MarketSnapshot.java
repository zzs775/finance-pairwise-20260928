package fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/** Immutable, versioned market snapshot: spot rates plus per-currency curves. */
public record MarketSnapshot(
        String version,
        LocalDate asOf,
        Map<Pair, BigDecimal> spotRates,
        Map<Ccy, DfCurve> curves) {

    public MarketSnapshot {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(spotRates, "spotRates");
        Objects.requireNonNull(curves, "curves");
        if (version.isBlank()) {
            throw new FxException(Failure.SNAPSHOT_INVALID, "snapshot version must not be blank");
        }
        Map<Pair, BigDecimal> spots = Map.copyOf(spotRates);
        for (Map.Entry<Pair, BigDecimal> entry : spots.entrySet()) {
            BigDecimal spot = entry.getValue();
            if (spot == null) {
                throw new FxException(Failure.CURVE_HOLE,
                        "missing spot rate for " + entry.getKey());
            }
            if (spot.signum() <= 0) {
                throw new FxException(Failure.SNAPSHOT_INVALID,
                        "non-positive spot rate for " + entry.getKey() + ": " + spot);
            }
        }
        Map<Ccy, DfCurve> curveMap = Map.copyOf(curves);
        for (DfCurve curve : curveMap.values()) {
            curve.validate();
            if (!curve.valueDate().isEqual(asOf)) {
                throw new FxException(Failure.SNAPSHOT_INVALID,
                        curve.ccy() + " curve value date " + curve.valueDate()
                                + " does not match snapshot as-of " + asOf);
            }
        }
        for (Pair pair : spots.keySet()) {
            requireCurve(curveMap, pair.base());
            requireCurve(curveMap, pair.counter());
        }
        spotRates = spots;
        curves = curveMap;
    }

    private static void requireCurve(Map<Ccy, DfCurve> curveMap, Ccy ccy) {
        if (!curveMap.containsKey(ccy)) {
            throw new FxException(Failure.CURVE_HOLE,
                    "snapshot has no discount curve for " + ccy);
        }
    }

    public BigDecimal spot(Pair pair) {
        BigDecimal direct = spotRates.get(pair);
        if (direct != null) {
            return Scales.rate(direct);
        }
        BigDecimal inverse = spotRates.get(pair.inverted());
        if (inverse != null) {
            return Scales.rate(Scales.div(BigDecimal.ONE, inverse));
        }
        throw new FxException(Failure.CURVE_HOLE, "no spot rate for " + pair);
    }

    public DfCurve curve(Ccy ccy) {
        DfCurve curve = curves.get(ccy);
        if (curve == null) {
            throw new FxException(Failure.CURVE_HOLE, "no discount curve for " + ccy);
        }
        return curve;
    }

    public void validate() {
        // Validation is performed eagerly in the canonical constructor.
    }
}
