package cli;

import fx.Failure;
import fx.Fixtures;
import fx.FxException;
import fx.Trade;
import fx.Valuation;
import fx.ValuationEngine;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Command-line entry point: values fixture trades against the published snapshot. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        Path root = Path.of("fixtures");
        String version = null;
        LocalDate tradeDate = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--fixtures" -> root = Path.of(args[++i]);
                case "--snapshot" -> version = args[++i];
                case "--trade-date" -> tradeDate = LocalDate.parse(args[++i]);
                case "--help" -> {
                    printUsage();
                    return;
                }
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    printUsage();
                    System.exit(2);
                }
            }
        }

        Fixtures.Bundle bundle = Fixtures.load(root);
        var snapshot = version == null
                ? bundle.store().current()
                : bundle.snapshots().get(version);
        if (snapshot == null) {
            throw new FxException(Failure.SNAPSHOT_INVALID, "unknown snapshot version: " + version);
        }
        if (tradeDate == null) {
            tradeDate = LocalDate.now(ZoneOffset.UTC);
        }

        ValuationEngine engine = new ValuationEngine(bundle.dateRules());
        System.out.printf("snapshot=%s asOf=%s tradeDate=%s (fixed zone UTC)%n",
                snapshot.version(), snapshot.asOf(), tradeDate);
        System.out.printf("%-10s %-10s %-9s %-16s %-16s %-10s %-10s%n",
                "trade", "delivery", "side", "npvForwardDiff", "npvCashFlow",
                "residual", "npv");
        for (Trade trade : bundle.trades()) {
            try {
                Valuation v = engine.value(trade, snapshot, tradeDate);
                System.out.printf("%-10s %-10s %-9s %-16s %-16s %-10s %-10s%n",
                        v.tradeId(), v.deliveryDate(), trade.side(),
                        v.npvForwardDifference().toPlainString(),
                        v.npvCashFlow().toPlainString(),
                        v.residual().toPlainString(),
                        v.npv().toPlainString());
            } catch (FxException ex) {
                System.out.printf("%-10s FAILED %s (%s)%n",
                        trade.id(), ex.getMessage(), ex.failure());
            }
        }
    }

    private static void printUsage() {
        System.out.println("Usage: cli.Main [--fixtures DIR] [--snapshot VERSION] "
                + "[--trade-date YYYY-MM-DD]");
    }
}
