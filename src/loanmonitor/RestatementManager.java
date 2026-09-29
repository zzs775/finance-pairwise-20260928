package loanmonitor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import loanmonitor.model.Alert;
import loanmonitor.model.AlertType;
import loanmonitor.model.BreachKey;
import loanmonitor.model.BreachRecord;
import loanmonitor.model.Fact;
import loanmonitor.model.MonitoringSnapshot;
import loanmonitor.model.Status;
import loanmonitor.model.TestPeriod;

/**
 * Manages financial-fact restatements. A corrected fact is stored as a new
 * version of the same logical key; the earliest affected test period is
 * identified and the whole horizon is rebuilt deterministically from that
 * period onward (shared code path with a full rebuild), guaranteeing
 * byte-identical output between incremental restatement and full rebuild.
 * A failed restatement never replaces the currently published snapshot.
 */
public final class RestatementManager {

    private final MonitoringEngine engine;

    public RestatementManager(MonitoringEngine engine) {
        this.engine = engine;
    }

    public record RestatementResult(
            MonitoringSnapshot snapshot,
            String earliestAffectedPeriod,
            List<Fact> supersededFacts) {
    }

    public RestatementResult restate(Fact correctedFact) {
        MonitoringSnapshot before = engine.currentSnapshot();
        List<Fact> factsBefore = engine.factStateSnapshot();
        String earliest = earliestAffectedPeriod(correctedFact);

        MonitoringSnapshot candidate;
        try {
            engine.registerFact(correctedFact);
            List<BreachRecord> prior = before == null ? List.of()
                    : before.breaches();
            DailyOracle oracle = new DailyOracle(engine, prior);
            LocalDate horizonEnd = horizonEnd();
            candidate = oracle.rebuild(engine.inception(), horizonEnd);
            candidate = supersedeResolvedBreaches(candidate);
        } catch (RuntimeException failure) {
            engine.restoreFactState(factsBefore);
            if (before != null) {
                engine.publish(before);
            }
            throw failure;
        }

        Alert restatementAlert = new Alert(candidate.asOf(),
                correctedFact.id(), earliest == null ? "" : earliest,
                AlertType.RESTATEMENT,
                "fact " + correctedFact.id() + " restated as version "
                        + correctedFact.version()
                        + (earliest == null ? "" : " from period " + earliest));
        MonitoringSnapshot published = appendAlert(candidate, restatementAlert);
        engine.publish(published);
        return new RestatementResult(published, earliest, List.of(correctedFact));
    }

    public MonitoringSnapshot fullRebuild() {
        List<BreachRecord> prior = engine.currentSnapshot() == null
                ? List.of() : engine.currentSnapshot().breaches();
        DailyOracle oracle = new DailyOracle(engine, prior);
        MonitoringSnapshot rebuilt = oracle.rebuild(engine.inception(), horizonEnd());
        rebuilt = supersedeResolvedBreaches(rebuilt);
        engine.publish(rebuilt);
        return rebuilt;
    }

    private static MonitoringSnapshot supersedeResolvedBreaches(
            MonitoringSnapshot snapshot) {
        List<BreachRecord> updated = new ArrayList<>();
        for (BreachRecord breach : snapshot.breaches()) {
            Status status = snapshot.statuses().get(breach.key());
            boolean resolved = status == Status.COMPLIANT;
            if (resolved && !breach.superseded()) {
                updated.add(new BreachRecord(breach.key(), breach.breachDate(),
                        breach.reason(), breach.metricTrace(), breach.waived(),
                        breach.waiverId(), true));
            } else {
                updated.add(breach);
            }
        }
        return new MonitoringSnapshot(snapshot.asOf(), snapshot.publishVersion(),
                snapshot.statuses(), snapshot.metrics(), List.copyOf(updated),
                snapshot.alerts());
    }

    /**
     * Verifies the incremental-restatement guarantee: rebuilding from scratch
     * with the same facts must serialize byte-for-byte identically.
     */
    public boolean rebuildMatches(MonitoringSnapshot incremental) {
        MonitoringSnapshot rebuilt = fullRebuild();
        return incremental.toCanonicalText().equals(rebuilt.toCanonicalText());
    }

    /**
     * Earliest period whose window contains the corrected fact's date
     * coordinates. Returns null when the fact cannot affect any test period.
     */
    public String earliestAffectedPeriod(Fact fact) {
        LocalDate anchor = fact.isFlow() ? fact.periodStart() : fact.asOf();
        String earliest = null;
        LocalDate earliestEnd = null;
        for (TestPeriod period : engine.periods()) {
            boolean affects;
            if (fact.isFlow()) {
                affects = !fact.periodEnd().isBefore(period.periodStart())
                        && !fact.periodStart().isAfter(period.periodEnd());
            } else {
                affects = !anchor.isBefore(period.periodStart())
                        && !anchor.isAfter(period.periodEnd());
            }
            if (affects && (earliestEnd == null
                    || period.periodEnd().isBefore(earliestEnd))) {
                earliestEnd = period.periodEnd();
                earliest = period.label();
            }
        }
        return earliest;
    }

    private LocalDate horizonEnd() {
        LocalDate end = engine.inception();
        for (TestPeriod period : engine.periods()) {
            if (period.periodEnd().isAfter(end)) {
                end = period.periodEnd();
            }
        }
        for (var payment : engine.payments()) {
            LocalDate paymentEdge = payment.paidDate() != null
                    ? payment.paidDate() : payment.dueDate();
            if (paymentEdge.isAfter(end)) {
                end = paymentEdge;
            }
        }
        return end;
    }

    private static MonitoringSnapshot appendAlert(MonitoringSnapshot snapshot,
                                                  Alert alert) {
        List<Alert> alerts = new ArrayList<>(snapshot.alerts());
        alerts.add(alert);
        return new MonitoringSnapshot(snapshot.asOf(), snapshot.publishVersion(),
                snapshot.statuses(), snapshot.metrics(), snapshot.breaches(),
                List.copyOf(alerts));
    }
}
