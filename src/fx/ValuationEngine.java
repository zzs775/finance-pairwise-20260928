package fx;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/** Orchestrates date rules, forward calculation and dual-method present valuation. */
public final class ValuationEngine {

    private final DateRules dateRules;
    private final ForwardCalculator forwardCalculator;

    public ValuationEngine(DateRules dateRules) {
        this.dateRules = Objects.requireNonNull(dateRules, "dateRules");
        this.forwardCalculator = new ForwardCalculator();
    }

    public Valuation value(Trade trade, MarketSnapshot snapshot, LocalDate tradeDate) {
        Objects.requireNonNull(trade, "trade");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(tradeDate, "tradeDate");

        Pair pair = trade.pair();
        LocalDate spotDate = dateRules.spotDate(tradeDate, pair);
        LocalDate delivery = dateRules.deliveryDate(spotDate, pair, trade.schedule());

        BigDecimal forward = forwardCalculator.forwardRate(pair, delivery, snapshot);
        BigDecimal dfBase = snapshot.curve(pair.base()).rawDiscountFactor(delivery);
        BigDecimal dfCounter = snapshot.curve(pair.counter()).rawDiscountFactor(delivery);
        BigDecimal spot = snapshot.spot(pair);

        BigDecimal notional = trade.baseNotional();
        BigDecimal strike = trade.contractedForward();

        // Method 1 (counter currency): cash-settle the forward difference
        // N*(F-K) in counter currency at delivery, discounted at DF_counter.
        BigDecimal npvForwardDifference = Scales.mul(
                Scales.mul(notional, Scales.sub(forward, strike)), dfCounter);

        // Method 2 (counter currency): receive N base, pay N*K counter.
        // Base leg PV = N * S * DF_base; counter leg PV = N * K * DF_counter.
        BigDecimal baseLegPv = Scales.mul(Scales.mul(notional, spot), dfBase);
        BigDecimal counterLegPv = Scales.mul(Scales.mul(notional, strike), dfCounter);
        BigDecimal npvCashFlow = Scales.sub(baseLegPv, counterLegPv);

        BigDecimal signedFwd = npvForwardDifference.multiply(BigDecimal.valueOf(trade.side().sign()),
                Scales.MC);
        BigDecimal signedCash = npvCashFlow.multiply(BigDecimal.valueOf(trade.side().sign()),
                Scales.MC);

        BigDecimal roundedFwd = Scales.money(signedFwd);
        BigDecimal roundedCash = Scales.money(signedCash);
        BigDecimal residual = Scales.sub(roundedFwd, roundedCash);
        if (residual.abs().compareTo(Scales.MONEY_TOLERANCE) > 0) {
            throw new FxException(Failure.TOLERANCE_EXCEEDED, trade.id()
                    + " valuation methods diverge by " + residual.toPlainString());
        }

        BigDecimal average = signedFwd.add(signedCash, Scales.MC)
                .divide(BigDecimal.valueOf(2), Scales.MC);
        BigDecimal npv;
        if (trade.side() == Side.BUY) {
            npv = average.setScale(Scales.MONEY_SCALE, RoundingMode.FLOOR);
        } else {
            npv = average.setScale(Scales.MONEY_SCALE, RoundingMode.CEILING);
        }
        BigDecimal conservativeResidual = Scales.money(npv.subtract(average, Scales.MC));

        return new Valuation(
                trade.id(),
                snapshot.version(),
                delivery,
                Scales.rate(forward),
                forward,
                roundedFwd,
                roundedCash,
                Scales.money(residual),
                conservativeResidual,
                npv);
    }
}
