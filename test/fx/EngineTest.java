package fx;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;

/** Test suite: reversed pairs, dual calendars, broken dates, interpolation
 * boundaries, long/short symmetry, snapshot switching and overflow. */
public final class EngineTest {

    private static final Path FIXTURES = Path.of("fixtures");
    private static final LocalDate TRADE_DATE = LocalDate.parse("2025-01-06");

    public static void main(String[] args) {
        Fixtures.Bundle bundle = Fixtures.load(FIXTURES);
        ValuationEngine engine = new ValuationEngine(bundle.dateRules());
        MarketSnapshot v1 = bundle.snapshots().get("v1");
        MarketSnapshot v2 = bundle.snapshots().get("v2");

        TestRunner runner = new TestRunner();

        runner.check("invalid currency code rejected",
                () -> TestRunner.assertThrows(Failure.INVALID_CURRENCY,
                        () -> Ccy.of("EU"), "bad code length"));

        runner.check("same-leg pair rejected",
                () -> TestRunner.assertThrows(Failure.INVALID_PAIR,
                        () -> Pair.of("EUR", "EUR"), "same legs"));

        runner.check("holiday order independence", () -> {
            Holidays a = Holidays.of(Ccy.of("EUR"),
                    Set.of(LocalDate.parse("2025-05-01"), LocalDate.parse("2025-01-01")));
            Holidays b = Holidays.of(Ccy.of("EUR"),
                    Set.of(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-05-01")));
            TestRunner.assertEquals(a, b, "calendar sets must compare equal regardless of order");
            TestRunner.assertTrue(!a.isBusinessDay(LocalDate.parse("2025-05-01")),
                    "holiday must be non-business");
            TestRunner.assertTrue(a.isBusinessDay(LocalDate.parse("2025-01-02")),
                    "weekday outside holidays must be business");
        });

        runner.check("spot date skips weekend and both calendars", () -> {
            LocalDate spot = bundle.dateRules().spotDate(TRADE_DATE, Pair.of("EUR", "USD"));
            TestRunner.assertEquals(LocalDate.parse("2025-01-08"), spot,
                    "T+2 common business day");
        });

        runner.check("standard tenor uses calendar month and does not re-roll", () -> {
            LocalDate spot = bundle.dateRules().spotDate(TRADE_DATE, Pair.of("EUR", "USD"));
            LocalDate delivery = bundle.dateRules()
                    .deliveryDate(spot, Pair.of("EUR", "USD"), new Schedule.Standard("3M"));
            TestRunner.assertEquals(LocalDate.parse("2025-04-08"), delivery,
                    "3M from 2025-01-08");
        });

        runner.check("broken date before spot rejected",
                () -> TestRunner.assertThrows(Failure.DATE_ORDER,
                        () -> bundle.dateRules().deliveryDate(
                                LocalDate.parse("2025-01-08"), Pair.of("EUR", "USD"),
                                new Schedule.Broken(LocalDate.parse("2025-01-07"))),
                        "broken before spot"));

        runner.check("broken date on holiday rejected",
                () -> TestRunner.assertThrows(Failure.DATE_ORDER,
                        () -> bundle.dateRules().deliveryDate(
                                LocalDate.parse("2025-01-08"), Pair.of("EUR", "USD"),
                                new Schedule.Broken(LocalDate.parse("2025-07-04"))),
                        "USD holiday"));

        runner.check("unknown standard tenor rejected",
                () -> TestRunner.assertThrows(Failure.ILLEGAL_TENOR,
                        () -> new Schedule.Standard("5M"), "bad tenor"));

        runner.check("curve hole outside span not extrapolated",
                () -> TestRunner.assertThrows(Failure.CURVE_HOLE,
                        () -> v1.curve(Ccy.of("EUR"))
                                .discountFactor(LocalDate.parse("2027-01-01")),
                        "beyond last node"));

        runner.check("query before value date rejected",
                () -> TestRunner.assertThrows(Failure.DATE_ORDER,
                        () -> v1.curve(Ccy.of("EUR"))
                                .discountFactor(LocalDate.parse("2024-12-31")),
                        "before value date"));

        runner.check("negative discount factor rejected",
                () -> TestRunner.assertThrows(Failure.NEGATIVE_DISCOUNT_FACTOR,
                        () -> new DfCurve(Ccy.of("EUR"), TRADE_DATE,
                                java.util.List.of(
                                        new DfCurve.Node(TRADE_DATE, BigDecimal.ONE),
                                        new DfCurve.Node(LocalDate.parse("2025-02-06"),
                                                new BigDecimal("-0.5")))),
                        "negative df node"));

        runner.check("node interpolation boundary equals tabulated node", () -> {
            BigDecimal atNode = v1.curve(Ccy.of("EUR"))
                    .discountFactor(LocalDate.parse("2025-04-07"));
            TestRunner.assertEqualsBd(new BigDecimal("0.9950250000"), atNode,
                    "interpolation at a node must equal the node df");
        });

        runner.check("log-df interpolation is interior and deterministic", () -> {
            BigDecimal first = v1.curve(Ccy.of("EUR"))
                    .discountFactor(LocalDate.parse("2025-03-15"));
            BigDecimal second = v1.curve(Ccy.of("EUR"))
                    .discountFactor(LocalDate.parse("2025-03-15"));
            TestRunner.assertEqualsBd(first, second, "deterministic repeat query");
            TestRunner.assertTrue(first.compareTo(new BigDecimal("0.9983029000")) < 0
                            && first.compareTo(new BigDecimal("0.9950250000")) > 0,
                    "interpolated df must sit between bracketing nodes");
        });

        runner.check("covered interest parity for forward rate", () -> {
            LocalDate delivery = LocalDate.parse("2025-04-08");
            BigDecimal forward = new ForwardCalculator()
                    .forwardRate(Pair.of("EUR", "USD"), delivery, v1);
            BigDecimal dfBase = v1.curve(Ccy.of("EUR")).rawDiscountFactor(delivery);
            BigDecimal dfCounter = v1.curve(Ccy.of("USD")).rawDiscountFactor(delivery);
            BigDecimal lhs = forward.multiply(dfCounter, Scales.MC);
            BigDecimal rhs = v1.spot(Pair.of("EUR", "USD")).multiply(dfBase, Scales.MC);
            BigDecimal gap = lhs.subtract(rhs, Scales.MC).abs();
            TestRunner.assertTrue(
                    gap.compareTo(new BigDecimal("1E-12")) < 0,
                    "F*DFc must equal S*DFb, gap=" + gap.toPlainString());
        });

        runner.check("broken-date trade values with both methods in tolerance", () -> {
            Trade broken = findTrade(bundle, "T2-BROKEN");
            Valuation valuation = engine.value(broken, v1, TRADE_DATE);
            TestRunner.assertEquals(LocalDate.parse("2025-05-15"),
                    valuation.deliveryDate(), "broken delivery preserved");
            TestRunner.assertTrue(
                    valuation.residual().abs().compareTo(Scales.MONEY_TOLERANCE) <= 0,
                    "residual within one minor unit");
        });

        runner.check("long and short are exact opposites", () -> {
            Valuation buy = engine.value(findTrade(bundle, "T1-3M"), v1, TRADE_DATE);
            Valuation sell = engine.value(findTrade(bundle, "T4-SHORT"), v1, TRADE_DATE);
            TestRunner.assertEqualsBd(BigDecimal.ZERO,
                    buy.npv().add(sell.npv()), "BUY + SELL NPV must net to zero");
        });

        runner.check("reversed pair inverts the economic result", () -> {
            Valuation direct = engine.value(findTrade(bundle, "T1-3M"), v1, TRADE_DATE);
            Trade reversed = new Trade("REV", Pair.of("USD", "EUR"), Side.BUY,
                    new BigDecimal("1080000.00"), new Schedule.Standard("3M"),
                    new BigDecimal("0.93023256"));
            Valuation rev = engine.value(reversed, v1, TRADE_DATE);
            BigDecimal inverseForward = Scales.div(BigDecimal.ONE, direct.forwardRate());
            BigDecimal gap = rev.forwardRate().subtract(inverseForward, Scales.MC).abs();
            TestRunner.assertTrue(gap.compareTo(new BigDecimal("1E-7")) <= 0,
                    "reversed forward must be the reciprocal, gap=" + gap.toPlainString());
        });

        runner.check("snapshot switch changes valuation", () -> {
            Trade trade = findTrade(bundle, "T1-3M");
            BigDecimal npvV1 = engine.value(trade, v1, TRADE_DATE).npv();
            BigDecimal npvV2 = engine.value(trade, v2, LocalDate.parse("2025-01-07")).npv();
            TestRunner.assertTrue(npvV1.compareTo(npvV2) != 0,
                    "v2 spot move must change the NPV: " + npvV1 + " vs " + npvV2);
            TestRunner.assertEquals("v1", v1.version(), "snapshot identity retained");
        });

        runner.check("failed publish keeps old snapshot (no mixed state)", () -> {
            SnapshotStore store = new SnapshotStore();
            store.publish(v1);
            DfCurve misDatedUsd = new DfCurve(Ccy.of("USD"),
                    LocalDate.parse("2025-01-02"),
                    v1.curve(Ccy.of("USD")).nodes());
            java.util.Map<Ccy, DfCurve> mixedCurves = new java.util.HashMap<>(
                    java.util.Map.of(Ccy.of("EUR"), v1.curve(Ccy.of("EUR"))));
            mixedCurves.put(Ccy.of("USD"), misDatedUsd);
            TestRunner.assertThrows(Failure.SNAPSHOT_INVALID,
                    () -> new MarketSnapshot("bad", v1.asOf(), v1.spotRates(), mixedCurves),
                    "mis-dated USD curve must reject candidate");
            TestRunner.assertEquals("v1", store.current().version(),
                    "store must still serve the old valid snapshot");
        });

        runner.check("arithmetic overflow classified for huge notional",
                () -> TestRunner.assertThrows(Failure.ARITHMETIC_OVERFLOW,
                        () -> new Trade("HUGE", Pair.of("EUR", "USD"), Side.BUY,
                                new BigDecimal("9000000000000000000.00"),
                                new Schedule.Standard("3M"), new BigDecimal("1.075")),
                        "notional beyond magnitude bound"));

        int exitCode = runner.run();
        System.exit(exitCode);
    }

    private static Trade findTrade(Fixtures.Bundle bundle, String id) {
        return bundle.trades().stream()
                .filter(trade -> trade.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing fixture trade " + id));
    }
}
