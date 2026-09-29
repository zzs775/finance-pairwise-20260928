package loanmonitor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import loanmonitor.model.Alert;
import loanmonitor.model.BreachKey;
import loanmonitor.model.CovenantDefinition;
import loanmonitor.model.Fact;
import loanmonitor.model.MetricResult;
import loanmonitor.model.MonitoringSnapshot;
import loanmonitor.model.PaymentEvent;
import loanmonitor.model.Status;
import loanmonitor.model.TestPeriod;
import loanmonitor.model.Waiver;

/**
 * Stateful facade owning definitions, versioned facts, payment events and
 * the current published snapshot. Facts are deduplicated by logical key and
 * versions are immutable; validation (units, overlaps, missing definitions)
 * happens here with classified {@link CovenantException}s.
 */
public final class MonitoringEngine {

    private final LocalDate inception;
    private final List<TestPeriod> periods;
    private final Map<String, CovenantDefinition> covenants;
    private final List<PaymentEvent> payments;
    private final List<Waiver> waivers;
    private final MetricCalculator calculator = new MetricCalculator();

    /** logicalKey -> version -> fact. Only the max version feeds evaluation. */
    private final Map<String, TreeVersion> factVersions = new LinkedHashMap<>();

    private MonitoringSnapshot published;

    private record TreeVersion(Fact fact) {
    }

    public MonitoringEngine(LocalDate inception,
                            List<TestPeriod> periods,
                            List<CovenantDefinition> covenants,
                            List<Fact> facts,
                            List<PaymentEvent> payments,
                            List<Waiver> waivers) {
        this.inception = inception;
        this.periods = List.copyOf(periods);
        this.payments = List.copyOf(payments);
        this.waivers = List.copyOf(waivers);

        Map<String, CovenantDefinition> sorted = new HashMap<>();
        for (CovenantDefinition covenant : covenants) {
            if (sorted.put(covenant.id(), covenant) != null) {
                throw new CovenantException(CovenantException.Code.DUPLICATE_FACT,
                        "duplicate covenant definition id " + covenant.id());
            }
        }
        this.covenants = Map.copyOf(sorted);

        validatePeriods(periods);
        for (Fact fact : facts) {
            registerFact(fact);
        }
        for (CovenantDefinition covenant : this.covenants.values()) {
            validateCovenantUnits(covenant);
        }
    }

    public LocalDate inception() {
        return inception;
    }

    public List<TestPeriod> periods() {
        return periods;
    }

    public Collection<CovenantDefinition> covenants() {
        return covenants.values();
    }

    public CovenantDefinition covenant(String id) {
        CovenantDefinition covenant = covenants.get(id);
        if (covenant == null) {
            throw new CovenantException(CovenantException.Code.MISSING_DEFINITION,
                    "no covenant definition with id " + id);
        }
        return covenant;
    }

    public List<PaymentEvent> payments() {
        return payments;
    }

    public List<Waiver> waivers() {
        return waivers;
    }

    /** Current (highest-version) facts, in a deterministic (key) order. */
    public List<Fact> currentFacts() {
        List<Fact> result = new ArrayList<>();
        for (TreeVersion tree : factVersions.values()) {
            result.add(tree.fact());
        }
        result.sort(java.util.Comparator.comparing(Fact::id)
                .thenComparingInt(Fact::version));
        return List.copyOf(result);
    }

    /**
     * Registers one fact. Same (logicalKey, version) is a duplicate; the same
     * logical key across versions must keep the same unit and shape.
     */
    public void registerFact(Fact fact) {
        validateShape(fact);
        String key = fact.logicalKey();
        TreeVersion existing = factVersions.get(key);
        if (existing == null) {
            factVersions.put(key, new TreeVersion(fact));
        } else {
            Fact prior = existing.fact();
            if (prior.version() == fact.version()) {
                throw new CovenantException(CovenantException.Code.DUPLICATE_FACT,
                        "duplicate fact " + fact.id() + " for key " + key
                                + " version " + fact.version());
            }
            if (prior.unit() != fact.unit()) {
                throw new CovenantException(CovenantException.Code.UNIT_CONFLICT,
                        "restated fact " + fact.id() + " changes unit from "
                                + prior.unit() + " to " + fact.unit());
            }
            if (prior.isFlow() != fact.isFlow()) {
                throw new CovenantException(CovenantException.Code.INVALID_PERIOD,
                        "restated fact " + fact.id() + " changes flow/stock shape");
            }
            if (fact.version() > prior.version()) {
                factVersions.put(key, new TreeVersion(fact));
            }
        }
        if (fact.isFlow()) {
            checkOverlap(fact);
        }
    }

    private static void validateShape(Fact fact) {
        if (fact.isFlow()) {
            if (fact.periodEnd().isBefore(fact.periodStart())) {
                throw new CovenantException(CovenantException.Code.INVALID_PERIOD,
                        "flow fact " + fact.id() + " ends before it starts");
            }
        } else if (fact.asOf() == null) {
            throw new CovenantException(CovenantException.Code.INVALID_PERIOD,
                    "stock fact " + fact.id() + " missing asOf date");
        }
    }

    private void checkOverlap(Fact candidate) {
        for (TreeVersion tree : factVersions.values()) {
            Fact other = tree.fact();
            if (other == candidate || !other.borrower().equals(candidate.borrower())
                    || !other.account().equals(candidate.account())
                    || !other.unit().compatibleWith(candidate.unit())
                    || other.version() != candidate.version()) {
                continue;
            }
            if (other.isFlow() && !other.periodEnd().isBefore(candidate.periodStart())
                    && !other.periodStart().isAfter(candidate.periodEnd())) {
                throw new CovenantException(CovenantException.Code.PERIOD_OVERLAP,
                        "flow facts " + candidate.id() + " and " + other.id()
                                + " overlap for account " + candidate.account());
            }
        }
    }

    private void validateCovenantUnits(CovenantDefinition covenant) {
        if (covenant.type() != loanmonitor.model.TestType.PAYMENT_DATE
                && covenant.metric() == null) {
            throw new CovenantException(CovenantException.Code.MISSING_DEFINITION,
                    "covenant " + covenant.id() + " missing metric definition");
        }
        if (covenant.metric() == null) {
            return;
        }
        if (covenant.metric().resultUnit() != null
                && covenant.threshold() == null) {
            throw new CovenantException(CovenantException.Code.MISSING_DEFINITION,
                    "covenant " + covenant.id() + " missing threshold");
        }
    }

    private static void validatePeriods(List<TestPeriod> periods) {
        List<TestPeriod> sorted = new ArrayList<>(periods);
        sorted.sort(java.util.Comparator.comparing(TestPeriod::periodEnd));
        for (int i = 0; i < sorted.size(); i++) {
            TestPeriod period = sorted.get(i);
            if (period.periodEnd().isBefore(period.periodStart())) {
                throw new CovenantException(CovenantException.Code.INVALID_PERIOD,
                        "test period " + period.label() + " ends before it starts");
            }
            for (int j = 0; j < i; j++) {
                TestPeriod earlier = sorted.get(j);
                if (!earlier.periodEnd().isBefore(period.periodStart())
                        && !period.periodEnd().isBefore(earlier.periodStart())) {
                    throw new CovenantException(CovenantException.Code.PERIOD_OVERLAP,
                            "test periods " + earlier.label() + " and "
                                    + period.label() + " overlap");
                }
            }
        }
    }

    public MetricResult evaluateMetric(CovenantDefinition covenant,
                                       TestPeriod period) {
        return calculator.calculate(covenant, period, currentFacts(), inception);
    }

    public MonitoringSnapshot snapshotAsOf(LocalDate logicalDate) {
        DailyOracle oracle = new DailyOracle(this);
        return oracle.runThrough(inception, logicalDate);
    }

    public Status statusOf(BreachKey key) {
        return published == null ? null : published.statuses().get(key);
    }

    public List<Alert> alerts() {
        return published == null ? List.of() : published.alerts();
    }

    public MonitoringSnapshot currentSnapshot() {
        return published;
    }

    void publish(MonitoringSnapshot snapshot) {
        this.published = snapshot;
    }

    /** Immutable capture of the versioned-fact state for rollback purposes. */
    public List<Fact> factStateSnapshot() {
        return currentFacts();
    }

    /** Restores facts to exactly the supplied previously captured state. */
    public void restoreFactState(List<Fact> facts) {
        factVersions.clear();
        for (Fact fact : facts) {
            String key = fact.logicalKey();
            factVersions.put(key, new TreeVersion(fact));
        }
    }
}
