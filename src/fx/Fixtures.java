package fx;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Loads all local synthetic fixture files (calendars, curves, spots, trades). */
public final class Fixtures {

    public record Bundle(
            DateRules dateRules,
            Map<String, MarketSnapshot> snapshots,
            SnapshotStore store,
            List<Trade> trades) {
    }

    public static Bundle load(Path root) {
        try {
            Map<Ccy, Holidays> calendars = loadCalendars(root.resolve("holidays"));
            Path marketDir = root.resolve("market");
            Map<String, LocalDate> versions =
                    loadVersions(marketDir.resolve("snapshots.csv"));
            Map<String, Map<Pair, BigDecimal>> spots =
                    loadSpots(marketDir.resolve("spot.csv"));
            Map<String, Map<Ccy, DfCurve>> curves =
                    loadCurves(marketDir.resolve("curves"), versions);

            Map<String, MarketSnapshot> snapshots = new LinkedHashMap<>();
            for (Map.Entry<String, LocalDate> entry : versions.entrySet()) {
                String version = entry.getKey();
                MarketSnapshot snapshot = new MarketSnapshot(
                        version,
                        entry.getValue(),
                        spots.getOrDefault(version, Map.of()),
                        curves.getOrDefault(version, Map.of()));
                snapshots.put(version, snapshot);
            }
            if (snapshots.isEmpty()) {
                throw new FxException(Failure.FIXTURE_ERROR, "no snapshot versions defined");
            }

            SnapshotStore store = new SnapshotStore();
            store.publish(snapshots.values().iterator().next());

            List<Trade> trades = loadTrades(root.resolve("trades").resolve("trades.csv"));
            return new Bundle(new DateRules(calendars), Map.copyOf(snapshots), store,
                    List.copyOf(trades));
        } catch (IOException ex) {
            throw new FxException(Failure.FIXTURE_ERROR,
                    "cannot read fixtures under " + root + ": " + ex.getMessage());
        }
    }

    private static List<String> lines(Path file) throws IOException {
        List<String> out = new ArrayList<>();
        for (String raw : Files.readAllLines(file)) {
            String line = raw.trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                out.add(line);
            }
        }
        return out;
    }

    private static Map<Ccy, Holidays> loadCalendars(Path dir) throws IOException {
        Map<Ccy, Holidays> calendars = new TreeMap<>(Comparator.comparing(Ccy::code));
        try (var paths = Files.list(dir)) {
            for (Path file : paths.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".txt")) {
                    continue;
                }
                Ccy ccy = Ccy.of(name.substring(0, name.length() - 4));
                var dates = new TreeSet<LocalDate>();
                for (String line : lines(file)) {
                    dates.add(DateRules.parseIsoDate(line));
                }
                calendars.put(ccy, Holidays.of(ccy, dates));
            }
        }
        return calendars;
    }

    private static Map<String, LocalDate> loadVersions(Path file) throws IOException {
        Map<String, LocalDate> versions = new LinkedHashMap<>();
        for (String line : lines(file)) {
            String[] parts = csv(line);
            if (parts.length != 2) {
                throw new FxException(Failure.FIXTURE_ERROR, "bad snapshots row: " + line);
            }
            versions.put(parts[0], DateRules.parseIsoDate(parts[1]));
        }
        return versions;
    }

    private static Map<String, Map<Pair, BigDecimal>> loadSpots(Path file)
            throws IOException {
        Map<String, Map<Pair, BigDecimal>> spots = new LinkedHashMap<>();
        for (String line : lines(file)) {
            String[] parts = csv(line);
            if (parts.length != 4) {
                throw new FxException(Failure.FIXTURE_ERROR, "bad spot row: " + line);
            }
            spots.computeIfAbsent(parts[0], k -> new LinkedHashMap<>())
                    .put(Pair.of(parts[1], parts[2]), new BigDecimal(parts[3]));
        }
        return spots;
    }

    private static Map<String, Map<Ccy, DfCurve>> loadCurves(
            Path dir, Map<String, LocalDate> versions) throws IOException {
        Map<String, Map<Ccy, List<DfCurve.Node>>> nodes = new LinkedHashMap<>();
        try (var paths = Files.list(dir)) {
            for (Path file : paths.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".csv")) {
                    continue;
                }
                Ccy ccy = Ccy.of(name.substring(0, name.length() - 4));
                for (String line : lines(file)) {
                    String[] parts = csv(line);
                    if (parts.length != 4) {
                        throw new FxException(Failure.FIXTURE_ERROR, "bad curve row: " + line);
                    }
                    String version = parts[0];
                    LocalDate valueDate = DateRules.parseIsoDate(parts[1]);
                    LocalDate expected = versions.get(version);
                    if (expected != null && !expected.isEqual(valueDate)) {
                        throw new FxException(Failure.SNAPSHOT_INVALID,
                                ccy + " curve value date " + valueDate
                                        + " does not match version as-of " + expected);
                    }
                    DfCurve.Node node = new DfCurve.Node(
                            DateRules.parseIsoDate(parts[2]), new BigDecimal(parts[3]));
                    nodes.computeIfAbsent(version, k -> new LinkedHashMap<>())
                            .computeIfAbsent(ccy, k -> new ArrayList<>())
                            .add(node);
                }
            }
        }
        Map<String, Map<Ccy, DfCurve>> curves = new LinkedHashMap<>();
        for (var versionEntry : nodes.entrySet()) {
            String version = versionEntry.getKey();
            LocalDate valueDate = versions.get(version);
            Map<Ccy, DfCurve> perVersion = new LinkedHashMap<>();
            for (var ccyEntry : versionEntry.getValue().entrySet()) {
                perVersion.put(ccyEntry.getKey(),
                        new DfCurve(ccyEntry.getKey(), valueDate, ccyEntry.getValue()));
            }
            curves.put(version, perVersion);
        }
        return curves;
    }

    private static List<Trade> loadTrades(Path file) throws IOException {
        List<Trade> trades = new ArrayList<>();
        for (String line : lines(file)) {
            String[] parts = csv(line);
            if (parts.length != 8) {
                throw new FxException(Failure.FIXTURE_ERROR, "bad trade row: " + line);
            }
            Pair pair = Pair.of(parts[1], parts[2]);
            Side side = switch (parts[3]) {
                case "BUY" -> Side.BUY;
                case "SELL" -> Side.SELL;
                default -> throw new FxException(Failure.FIXTURE_ERROR,
                        "unknown side in trade " + parts[0] + ": " + parts[3]);
            };
            BigDecimal notional = new BigDecimal(parts[4]);
            Schedule schedule;
            if ("BROKEN".equals(parts[5])) {
                if (parts[6].isBlank()) {
                    throw new FxException(Failure.FIXTURE_ERROR,
                            "broken trade " + parts[0] + " missing delivery date");
                }
                schedule = new Schedule.Broken(DateRules.parseIsoDate(parts[6]));
            } else {
                schedule = new Schedule.Standard(parts[5]);
            }
            trades.add(new Trade(parts[0], pair, side, notional, schedule,
                    new BigDecimal(parts[7])));
        }
        return trades;
    }

    private static String[] csv(String line) {
        String[] parts = line.split(",", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }
}
