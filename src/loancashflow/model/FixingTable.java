package loancashflow.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Versioned fixing observations; the latest version for a reset date wins. */
public record FixingTable(Map<LocalDate, List<FixingObservation>> byResetDate) {

    public FixingTable {
        byResetDate = byResetDate == null ? Map.of() : Map.copyOf(byResetDate);
    }

    public static FixingTable of(FixingObservation... observations) {
        return ofList(java.util.Arrays.asList(observations));
    }

    public static FixingTable ofList(List<FixingObservation> observations) {
        Map<LocalDate, List<FixingObservation>> grouped = new HashMap<>();
        for (FixingObservation observation : observations) {
            grouped.computeIfAbsent(observation.resetDate(), key -> new ArrayList<>()).add(observation);
        }
        grouped.values().forEach(list ->
                list.sort(Comparator.comparingInt(FixingObservation::version)));
        return new FixingTable(grouped);
    }

    /** Returns the latest-version raw rate for the date, or null when missing. */
    public BigDecimal latest(LocalDate resetDate) {
        List<FixingObservation> observations = byResetDate.get(resetDate);
        if (observations == null || observations.isEmpty()) {
            return null;
        }
        return observations.get(observations.size() - 1).rawRate();
    }

    /** Returns the observation visible at the given as-of version, or null when missing. */
    public BigDecimal asOf(LocalDate resetDate, int versionInclusive) {
        List<FixingObservation> observations = byResetDate.get(resetDate);
        if (observations == null) {
            return null;
        }
        FixingObservation best = null;
        for (FixingObservation observation : observations) {
            if (observation.version() <= versionInclusive
                    && (best == null || observation.version() > best.version())) {
                best = observation;
            }
        }
        return best == null ? null : best.rawRate();
    }
}
