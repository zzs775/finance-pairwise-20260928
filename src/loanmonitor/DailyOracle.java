package loanmonitor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import loanmonitor.model.Alert;
import loanmonitor.model.AlertType;
import loanmonitor.model.BreachKey;
import loanmonitor.model.BreachRecord;
import loanmonitor.model.CovenantDefinition;
import loanmonitor.model.MetricResult;
import loanmonitor.model.MonitoringSnapshot;
import loanmonitor.model.PaymentEvent;
import loanmonitor.model.Status;
import loanmonitor.model.TestPeriod;
import loanmonitor.model.TestType;
import loanmonitor.model.Waiver;

/**
 * Day-by-day state-advance oracle. Starting from a start date it advances the
 * injected logical date one day at a time through an inclusive end date,
 * running metric tests on period certification dates, processing payment
 * due/paid events, expiry of grace windows, and waiver boundaries. Every
 * status change and alert carries an exact logical date.
 */
public final class DailyOracle {

    private final MonitoringEngine engine;
    private final StateMachine machine = new StateMachine();

    private final Map<BreachKey, Tracked> tracks = new HashMap<>();
    private final Map<BreachKey, MetricResult> metrics = new HashMap<>();
    private final List<BreachRecord> breaches = new ArrayList<>();
    private final List<Alert> alerts = new ArrayList<>();
    private LocalDate cursor;
    private final List<BreachRecord> priorBreachEvidence;

    public DailyOracle(MonitoringEngine engine) {
        this(engine, List.of());
    }

    public DailyOracle(MonitoringEngine engine, List<BreachRecord> priorBreaches) {
        this.engine = engine;
        this.priorBreachEvidence = List.copyOf(priorBreaches);
        for (CovenantDefinition covenant : engine.covenants()) {
            for (TestPeriod period : engine.periods()) {
                tracks.put(new BreachKey(covenant.id(), period.label()),
                        new Tracked(Status.COMPLIANT));
            }
        }
    }

    private static final class Tracked {
        Status status;
        LocalDate breachDate;
        String breachReason;
        String metricTrace;
        String waiverId;

        private Tracked(Status status) {
            this.status = status;
        }
    }

    public MonitoringSnapshot runThrough(LocalDate startInclusive,
                                         LocalDate endInclusive) {
        this.cursor = startInclusive;
        while (!cursor.isAfter(endInclusive)) {
            advanceOneDay(cursor);
            cursor = cursor.plusDays(1);
        }
        MonitoringSnapshot snapshot = buildSnapshot(endInclusive);
        engine.publish(snapshot);
        return snapshot;
    }

    /**
     * Rebuilds all tracks from scratch using the supplied horizon and returns
     * the snapshot without relying on prior state. Used by RestatementManager
     * so incremental and full rebuilds share exactly one code path.
     */
    public MonitoringSnapshot rebuild(LocalDate startInclusive,
                                      LocalDate endInclusive) {
        tracks.clear();
        metrics.clear();
        breaches.clear();
        alerts.clear();
        breaches.addAll(priorBreachEvidence);
        for (CovenantDefinition covenant : engine.covenants()) {
            for (TestPeriod period : engine.periods()) {
                tracks.put(new BreachKey(covenant.id(), period.label()),
                        new Tracked(Status.COMPLIANT));
            }
        }
        return runThrough(startInclusive, endInclusive);
    }

    private void advanceOneDay(LocalDate date) {
        List<CovenantDefinition> covenantList = sortedCovenants();
        for (CovenantDefinition covenant : covenantList) {
            if (covenant.type() == TestType.PAYMENT_DATE) {
                processPaymentCovenant(covenant, date);
            } else {
                processFinancialCovenant(covenant, date);
            }
        }
    }

    private void processFinancialCovenant(CovenantDefinition covenant,
                                          LocalDate date) {
        for (TestPeriod period : engine.periods()) {
            if (!period.periodEnd().equals(date)) {
                continue;
            }
            BreachKey key = new BreachKey(covenant.id(), period.label());
            Tracked tracked = tracks.get(key);
            MetricResult result = engine.evaluateMetric(covenant, period);
            metrics.put(key, result);
            boolean failed = failsThreshold(covenant, result);
            Waiver waiver = activeWaiver(covenant.id(), period.label(), date);
            if (failed) {
                recordBreach(key, tracked, date,
                        thresholdReason(covenant, result), result.formulaTrace(),
                        waiver, AlertType.BREACH_DETECTED);
            } else {
                apply(key, tracked, machine.onTestPass(key, tracked.status, date,
                        covenant.graceDays(), tracked.breachDate));
            }
        }
        advanceGrace(covenant, date);
    }

    private void processPaymentCovenant(CovenantDefinition covenant,
                                        LocalDate date) {
        List<PaymentEvent> duePayments = new ArrayList<>();
        for (PaymentEvent payment : engine.payments()) {
            if (!payment.borrower().equals(covenant.borrower())
                    || !payment.scheduleRef().equals(covenant.id())) {
                continue;
            }
            duePayments.add(payment);
        }
        for (PaymentEvent payment : duePayments) {
            String periodLabel = labelForPayment(payment.dueDate());
            BreachKey key = new BreachKey(covenant.id(), periodLabel);
            Tracked tracked = tracks.computeIfAbsent(key,
                    k -> new Tracked(Status.COMPLIANT));
            Waiver waiver = activeWaiver(covenant.id(), periodLabel, date);
            if (payment.paidDate() != null && payment.paidDate().equals(date)) {
                apply(key, tracked, machine.onTestPass(key, tracked.status, date,
                        covenant.graceDays(), tracked.breachDate));
            } else if (payment.dueDate().equals(date)
                    && (payment.paidDate() == null
                    || payment.paidDate().isAfter(date))) {
                recordBreach(key, tracked, date,
                        "payment " + payment.id() + " not paid on due date "
                                + payment.dueDate(),
                        "payment " + payment.amount().toPlainString()
                                + " " + payment.unit(),
                        waiver, AlertType.BREACH_DETECTED);
            }
            boolean stillOutstanding = !date.isBefore(payment.dueDate())
                    && (payment.paidDate() == null || payment.paidDate().isAfter(date));
            apply(key, tracked, machine.onDayAdvanced(key, tracked.status, date,
                    covenant.graceDays(), tracked.breachDate, stillOutstanding,
                    waiver));
        }
    }

    private void advanceGrace(CovenantDefinition covenant, LocalDate date) {
        for (TestPeriod period : engine.periods()) {
            BreachKey key = new BreachKey(covenant.id(), period.label());
            Tracked tracked = tracks.get(key);
            if (tracked == null || tracked.breachDate == null) {
                continue;
            }
            Waiver waiver = activeWaiver(covenant.id(), period.label(), date);
            apply(key, tracked, machine.onDayAdvanced(key, tracked.status, date,
                    covenant.graceDays(), tracked.breachDate, true, waiver));
        }
    }

    private void recordBreach(BreachKey key, Tracked tracked, LocalDate date,
                              String reason, String trace, Waiver waiver,
                              AlertType alertType) {
        BreachRecord existing = null;
        for (BreachRecord breach : breaches) {
            if (breach.key().equals(key)) {
                existing = breach;
                break;
            }
        }
        if (tracked.breachDate == null) {
            if (existing == null) {
                tracked.breachDate = date;
                tracked.breachReason = reason;
                tracked.metricTrace = trace;
                breaches.add(new BreachRecord(key, date, reason, trace,
                        false, null, false));
            } else {
                tracked.breachDate = existing.breachDate();
                tracked.breachReason = existing.reason();
                tracked.metricTrace = existing.metricTrace();
                tracked.waiverId = existing.waiverId();
            }
        }
        apply(key, tracked, machine.onTestFailure(key, tracked.status, date, waiver));
    }

    private void apply(BreachKey key, Tracked tracked,
                       StateMachine.Transition transition) {
        if (transition.from() == transition.to()) {
            return;
        }
        tracked.status = transition.to();
        AlertType type = mapAlert(transition.to());
        String detail = transition.reason();
        if (transition.to() == Status.WAIVED) {
            Waiver waiver = activeWaiver(key.covenantId(), key.periodLabel(), cursor);
            if (waiver != null) {
                tracked.waiverId = waiver.id();
                markWaived(key, waiver.id());
            }
        }
        alerts.add(new Alert(cursor, key.covenantId(), key.periodLabel(),
                type, detail));
    }

    private AlertType mapAlert(Status to) {
        return switch (to) {
            case BREACHED -> AlertType.BREACH_DETECTED;
            case CURE_PENDING -> AlertType.CURE_PENDING;
            case COMPLIANT -> AlertType.CURED;
            case DEFAULTED -> AlertType.DEFAULTED;
            case WAIVED -> AlertType.WAIVER_APPLIED;
        };
    }

    private void markWaived(BreachKey key, String waiverId) {
        breaches.replaceAll(breach -> breach.key().equals(key)
                ? new BreachRecord(breach.key(), breach.breachDate(),
                breach.reason(), breach.metricTrace(), true, waiverId,
                breach.superseded())
                : breach);
    }

    private static boolean failsThreshold(CovenantDefinition covenant,
                                          MetricResult result) {
        int cmp = result.value().compareTo(covenant.threshold());
        return switch (covenant.type()) {
            case RATIO, MINIMUM -> cmp < 0;
            case MAXIMUM -> cmp > 0;
            case PAYMENT_DATE ->
                    throw new CovenantException(CovenantException.Code.MISSING_DEFINITION,
                            "payment covenants do not use metric thresholds");
        };
    }

    private static String thresholdReason(CovenantDefinition covenant,
                                          MetricResult result) {
        String operator = switch (covenant.type()) {
            case RATIO, MINIMUM -> ">=";
            case MAXIMUM -> "<=";
            case PAYMENT_DATE -> "n/a";
        };
        return covenant.type() + " value " + result.value().toPlainString()
                + " fails " + operator + " threshold "
                + covenant.threshold().toPlainString();
    }

    private String labelForPayment(LocalDate dueDate) {
        for (TestPeriod period : engine.periods()) {
            if (!dueDate.isBefore(period.periodStart())
                    && !dueDate.isAfter(period.periodEnd())) {
                return period.label();
            }
        }
        return "payment-" + dueDate;
    }

    private Waiver activeWaiver(String covenantId, String periodLabel,
                                LocalDate date) {
        Waiver chosen = null;
        for (Waiver waiver : engine.waivers()) {
            if (waiver.covenantId().equals(covenantId)
                    && waiver.periodLabels().contains(periodLabel)
                    && !date.isBefore(waiver.effectiveDate())
                    && !date.isAfter(waiver.expiryDate())
                    && (chosen == null || waiver.id().compareTo(chosen.id()) < 0)) {
                chosen = waiver;
            }
        }
        return chosen;
    }

    private List<CovenantDefinition> sortedCovenants() {
        List<CovenantDefinition> list = new ArrayList<>(engine.covenants());
        list.sort(java.util.Comparator.comparing(CovenantDefinition::id));
        return list;
    }

    private MonitoringSnapshot buildSnapshot(LocalDate asOf) {
        Map<BreachKey, Status> statusMap = new HashMap<>();
        tracks.forEach((key, tracked) -> statusMap.put(key, tracked.status));
        int version = engine.currentSnapshot() == null ? 1
                : engine.currentSnapshot().publishVersion() + 1;
        return new MonitoringSnapshot(asOf, version,
                Map.copyOf(statusMap), Map.copyOf(metrics),
                List.copyOf(breaches), List.copyOf(alerts));
    }

    public List<Alert> alertsFor(BreachKey key) {
        List<Alert> result = new ArrayList<>();
        for (Alert alert : alerts) {
            if (alert.covenantId().equals(key.covenantId())
                    && alert.periodLabel().equals(key.periodLabel())) {
                result.add(alert);
            }
        }
        return List.copyOf(result);
    }

    public Status statusOn(BreachKey key, LocalDate logicalDate) {
        Status status = Status.COMPLIANT;
        for (Alert alert : alerts) {
            if (alert.covenantId().equals(key.covenantId())
                    && alert.periodLabel().equals(key.periodLabel())
                    && !alert.date().isAfter(logicalDate)) {
                status = switch (alert.type()) {
                    case BREACH_DETECTED -> Status.BREACHED;
                    case CURE_PENDING -> Status.CURE_PENDING;
                    case CURED -> Status.COMPLIANT;
                    case DEFAULTED, GRACE_EXPIRED -> Status.DEFAULTED;
                    case WAIVER_APPLIED -> Status.WAIVED;
                    case RESTATEMENT -> status;
                };
            }
        }
        return status;
    }
}
