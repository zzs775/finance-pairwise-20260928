package covenant;

import java.time.LocalDate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Engine {

    private final Evaluator evaluator;

    public Engine(Evaluator evaluator) {
        this.evaluator = evaluator;
    }

    public Snapshot rebuildAll(LocalDate asOf, List<PeriodKey> periods) {
        List<TestResult> results = new ArrayList<>();
        for (PeriodKey period : periods.stream().sorted().toList()) {
            for (CovenantTest test : evaluator.tests()) {
                TestResult result = evaluator.evaluateAt(period.borrower(), test,
                        period.start(), period.end(), asOf);
                results.add(result);
            }
        }
        return new Snapshot(asOf, results, List.of());
    }

    public RestatementResult restate(FactStore published,
                                     FactStore candidate,
                                     Snapshot currentPublished,
                                     LocalDate asOf, List<PeriodKey> periods,
                                     String borrower) {
        Snapshot rebuilt;
        try {
            Evaluator candidateEvaluator = new Evaluator(candidate,
                    evaluator.catalog(), evaluator.tests(),
                    evaluator.waivers(), evaluator.payments());
            rebuilt = new Engine(candidateEvaluator).rebuildAll(asOf, periods);
        } catch (CovenantException e) {
            return new RestatementResult(currentPublished, List.of(), false, false);
        }
        List<PeriodKey> affected = earliestAffectedPeriods(published, candidate, periods, borrower);
        List<Alert> incrementalAlerts = new java.util.ArrayList<>();
        for (PeriodKey period : affected) {
            for (TestResult newResult : rebuilt.results()) {
                if (!matches(newResult, period)) {
                    continue;
                }
                TestResult oldResult = currentPublished.results().stream()
                        .filter(r -> matches(r, period)).findFirst().orElse(null);
                if (oldResult == null
                        || oldResult.status() != newResult.status()
                        || !java.util.Objects.equals(oldResult.value(), newResult.value())) {
                    incrementalAlerts.add(new Alert(asOf, period.borrower(), newResult.testId(),
                            Periods.label(period.start(), period.end()),
                            AlertType.RESTATEMENT_CHANGED,
                            "restated " + statusOf(oldResult) + " -> " + newResult.status()
                                    + " value="
                                    + (newResult.value() == null ? "" : newResult.value())));
                }
            }
        }
        // Determinism check: rebuilding the candidate twice must produce byte-identical text.
        Evaluator verifyEvaluator = new Evaluator(candidate,
                evaluator.catalog(), evaluator.tests(),
                evaluator.waivers(), evaluator.payments());
        Snapshot secondRebuild = new Engine(verifyEvaluator).rebuildAll(asOf, periods);
        boolean byteIdentical = java.util.Arrays.equals(rebuilt.canonicalBytes(),
                secondRebuild.canonicalBytes());
        return new RestatementResult(rebuilt,
                incrementalAlerts.stream()
                        .sorted(Comparator.comparing(Alert::date)
                                .thenComparing(Alert::borrower)
                                .thenComparing(Alert::testId)
                                .thenComparing(Alert::periodLabel)
                                .thenComparing(Alert::type))
                        .toList(),
                byteIdentical, byteIdentical);
    }

    private static boolean matches(TestResult result, PeriodKey period) {
        return result.borrower().equals(period.borrower())
                && result.periodStart().equals(period.start())
                && result.periodEnd().equals(period.end());
    }

    private static String statusOf(TestResult result) {
        return result == null ? "ABSENT" : result.status().name();
    }

    private List<PeriodKey> earliestAffectedPeriods(FactStore published, FactStore candidate,
                                                    List<PeriodKey> periods, String borrower) {
        List<PeriodKey> affected = new java.util.ArrayList<>();
        LocalDate earliest = null;
        for (Fact newFact : candidate.all()) {
            if (!newFact.borrower().equals(borrower)) {
                continue;
            }
            boolean isNewVersion = published.allVersions(newFact.borrower(), newFact.metricId(),
                    newFact.periodStart(), newFact.periodEnd()).stream()
                    .noneMatch(f -> f.version() != null && f.version().equals(newFact.version()));
            if (isNewVersion
                    && (earliest == null || newFact.periodStart().isBefore(earliest))) {
                earliest = newFact.periodStart();
            }
        }
        if (earliest == null) {
            return List.of();
        }
        for (PeriodKey period : periods) {
            if (period.borrower().equals(borrower) && !period.end().isBefore(earliest)) {
                affected.add(period);
            }
        }
        return affected;
    }

    public record PeriodKey(String borrower, LocalDate start, LocalDate end)
            implements Comparable<PeriodKey> {
        @Override
        public int compareTo(PeriodKey other) {
            int byBorrower = borrower.compareTo(other.borrower);
            if (byBorrower != 0) {
                return byBorrower;
            }
            int byStart = start.compareTo(other.start);
            return byStart != 0 ? byStart : end.compareTo(other.end);
        }
    }
}
