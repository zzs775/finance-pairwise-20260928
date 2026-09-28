package covenant;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class FactStore {

    private final List<Fact> facts = new ArrayList<>();

    public FactStore() {
    }

    public void add(Fact fact, Unit expectedUnit) {
        if (fact == null) {
            throw new InvalidInputException("fact must not be null");
        }
        if (fact.borrower() == null || fact.borrower().isBlank()
                || fact.metricId() == null || fact.metricId().isBlank()) {
            throw new InvalidInputException("fact borrower and metricId must not be blank");
        }
        if (fact.value() == null) {
            throw new InvalidInputException("fact value must not be null");
        }
        if (fact.periodStart() == null || fact.periodEnd() == null || fact.asOf() == null) {
            throw new InvalidInputException("fact period bounds and asOf must not be null");
        }
        if (!fact.periodEnd().isAfter(fact.periodStart())) {
            throw new InvalidInputException("fact period end must be strictly after start: "
                    + fact.metricId());
        }
        if (expectedUnit != null && !fact.unit().compatibleWith(expectedUnit)) {
            throw new UnitConflictException("unit conflict for metric " + fact.metricId()
                    + ": expected " + expectedUnit.name() + " but got " + fact.unit().name());
        }
        for (Fact existing : facts) {
            if (!existing.borrower().equals(fact.borrower())
                    || !existing.metricId().equals(fact.metricId())) {
                continue;
            }
            if (!existing.unit().compatibleWith(fact.unit())) {
                throw new UnitConflictException("unit conflict for metric " + fact.metricId()
                        + " of borrower " + fact.borrower() + ": existing "
                        + existing.unit().name() + " vs new " + fact.unit().name());
            }
            // Same exact period with a different version is an accepted restatement.
            if (sameKey(existing, fact)
                    && existing.version() != null && existing.version().equals(fact.version())) {
                throw new DuplicateFactException("duplicate fact: " + identity(fact)
                        + " version=" + fact.version());
            }
            if (!sameKey(existing, fact)
                    && Periods.overlaps(existing.periodStart(), existing.periodEnd(),
                    fact.periodStart(), fact.periodEnd())) {
                throw new OverlappingPeriodException("overlapping periods for metric "
                        + fact.metricId() + " of borrower " + fact.borrower()
                        + ": existing " + Periods.label(existing.periodStart(), existing.periodEnd())
                        + " vs new " + Periods.label(fact.periodStart(), fact.periodEnd()));
            }
        }
        facts.add(fact);
        // Keep canonical ordering independent of insertion order: facts are sorted.
        facts.sort(canonicalOrder);
    }

    public void addAll(List<Fact> additions, Unit expectedUnit) {
        for (Fact fact : additions) {
            add(fact, expectedUnit);
        }
    }

    private static boolean sameKey(Fact a, Fact b) {
        return a.borrower().equals(b.borrower())
                && a.metricId().equals(b.metricId())
                && a.periodStart().equals(b.periodStart())
                && a.periodEnd().equals(b.periodEnd());
    }

    public List<Fact> all() {
        return List.copyOf(facts);
    }

    public Optional<Fact> find(String borrower, String metricId,
                               LocalDate periodStart, LocalDate periodEnd) {
        return facts.stream()
                .filter(f -> f.borrower().equals(borrower)
                        && f.metricId().equals(metricId)
                        && f.periodStart().equals(periodStart)
                        && f.periodEnd().equals(periodEnd))
                .max(versionOrder);
    }

    public Optional<Fact> findAsOf(String borrower, String metricId,
                                   LocalDate periodStart, LocalDate periodEnd,
                                   LocalDate asOf) {
        return facts.stream()
                .filter(f -> f.borrower().equals(borrower)
                        && f.metricId().equals(metricId)
                        && f.periodStart().equals(periodStart)
                        && f.periodEnd().equals(periodEnd)
                        && !f.asOf().isAfter(asOf))
                .max(versionOrder);
    }

    public List<Fact> allVersions(String borrower, String metricId,
                                  LocalDate periodStart, LocalDate periodEnd) {
        return facts.stream()
                .filter(f -> f.borrower().equals(borrower)
                        && f.metricId().equals(metricId)
                        && f.periodStart().equals(periodStart)
                        && f.periodEnd().equals(periodEnd))
                .sorted(versionOrder)
                .toList();
    }

    public List<Fact> inWindow(String borrower, String metricId,
                               LocalDate fromInclusive, LocalDate toExclusive) {
        return facts.stream()
                .filter(f -> f.borrower().equals(borrower)
                        && f.metricId().equals(metricId)
                        && !f.periodStart().isBefore(fromInclusive)
                        && !f.periodEnd().isAfter(toExclusive))
                .collect(java.util.stream.Collectors.groupingBy(
                        f -> f.periodStart() + "|" + f.periodEnd(),
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.maxBy(versionOrder)))
                .values().stream()
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(Fact::periodStart))
                .toList();
    }

    private static String identity(Fact f) {
        return f.borrower() + "/" + f.metricId() + "/"
                + Periods.label(f.periodStart(), f.periodEnd());
    }

    static final Comparator<Fact> versionOrder =
            Comparator.comparing(Fact::asOf)
                    .thenComparing(f -> f.version() == null ? "" : f.version());

    static final Comparator<Fact> canonicalOrder =
            Comparator.comparing(Fact::borrower)
                    .thenComparing(Fact::metricId)
                    .thenComparing(Fact::periodStart)
                    .thenComparing(Fact::periodEnd)
                    .thenComparing(versionOrder);
}
