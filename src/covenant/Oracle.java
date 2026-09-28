package covenant;

import java.time.LocalDate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Oracle {

    private final Evaluator evaluator;
    private final StateMachine stateMachine;
    private final SimulationClock clock;
    private final List<BorrowerSchedule> schedules = new ArrayList<>();
    private final Map<Key, TrackedState> states = new LinkedHashMap<>();
    private final List<Alert> alerts = new ArrayList<>();

    public Oracle(Evaluator evaluator, StateMachine stateMachine, SimulationClock clock) {
        this.evaluator = evaluator;
        this.stateMachine = stateMachine;
        this.clock = clock;
    }

    public void registerBorrowerPeriods(String borrower, LocalDate firstPeriodStart, int monthsPerPeriod, int periodCount) {
        if (monthsPerPeriod <= 0) {
            throw new InvalidInputException("monthsPerPeriod must be positive");
        }
        List<Engine.PeriodKey> keys = new ArrayList<>();
        LocalDate start = firstPeriodStart;
        for (int i = 0; i < periodCount; i++) {
            LocalDate end = start.plusMonths(monthsPerPeriod);
            keys.add(new Engine.PeriodKey(borrower, start, end));
            start = end;
        }
        schedules.add(new BorrowerSchedule(borrower, keys));
    }

    public void advanceTo(LocalDate targetDate) {
        if (targetDate.isBefore(clock.today())) {
            throw new InvalidInputException(
                    "cannot move clock backwards: " + clock.today() + " -> " + targetDate);
        }
        while (!clock.today().isAfter(targetDate)) {
            stepDay();
            if (clock.today().equals(targetDate)) {
                break;
            }
            clock.advanceDays(1);
        }
    }

    public Snapshot snapshot() {
        List<TestResult> results = new ArrayList<>();
        for (Map.Entry<Key, TrackedState> entry : states.entrySet()) {
            Key key = entry.getKey();
            TrackedState state = entry.getValue();
            results.add(new TestResult(key.borrower, key.testId,
                    key.periodStart, key.periodEnd,
                    state.status, state.value, state.explanation,
                    List.copyOf(state.evidence),
                    state.firstBreachDate, clock.today()));
        }
        return new Snapshot(clock.today(), results, new ArrayList<>(alerts));
    }

    public List<Alert> alerts() {
        return List.copyOf(alerts);
    }

    private void stepDay() {
        LocalDate today = clock.today();
        for (BorrowerSchedule schedule : schedules) {
            for (Engine.PeriodKey period : schedule.periods()) {
                // Boundary semantics: test becomes measurable on the exclusive end date.
                if (today.isBefore(period.end())) {
                    continue;
                }
                for (CovenantTest test : evaluator.tests()) {
                    Key key = new Key(period.borrower(), test.id(),
                            period.start(), period.end());
                    TrackedState state = states.computeIfAbsent(key,
                            ignored -> new TrackedState());
                    TestResult result = evaluator.evaluateAt(period.borrower(), test,
                            period.start(), period.end(), today);
                    boolean rawBreach = !result.breachEvidence().isEmpty()
                            || result.status() == Status.BREACHED
                            || result.status() == Status.WAIVED;
                    boolean waived = result.status() == Status.WAIVED;
                    if (rawBreach) {
                        if (state.firstBreachDate == null) {
                            state.firstBreachDate = breachOnset(evaluator, test,
                                    period.borrower(), period.end());
                        }
                        for (String evidenceItem : result.breachEvidence()) {
                            if (!state.evidence.contains(evidenceItem)) {
                                state.evidence.add(evidenceItem);
                            }
                        }
                    }
                    boolean withinGrace = rawBreach && state.firstBreachDate != null
                            && !today.isAfter(state.firstBreachDate.plusDays(test.graceDays()));
                    state.value = result.value();
                    state.explanation = result.explanation();
                    StateMachine.Transition transition = stateMachine.step(state.status,
                            new StateMachine.Input(rawBreach, withinGrace, waived,
                                    state.firstBreachDate, today));
                    if (transition.alert() != null) {
                        alerts.add(new Alert(today, period.borrower(), test.id(),
                                Periods.label(period.start(), period.end()),
                                transition.alert(),
                                describe(transition.alert(), test, state)));
                    }
                    state.status = transition.to();
                }
            }
        }
        alerts.sort(Comparator.comparing(Alert::date)
                .thenComparing(Alert::borrower)
                .thenComparing(Alert::testId)
                .thenComparing(Alert::periodLabel)
                .thenComparing(Alert::type));
    }

    private String describe(AlertType type, CovenantTest test, TrackedState state) {
        return type + " test=" + test.id()
                + (state.firstBreachDate == null ? ""
                        : " firstBreach=" + state.firstBreachDate)
                + " graceDays=" + test.graceDays();
    }

    private LocalDate breachOnset(Evaluator eval, CovenantTest test,
                                  String borrower, LocalDate periodEnd) {
        if (test.kind() == TestKind.PAYMENT_DATE) {
            return eval.payments().stream()
                    .filter(p -> p.borrower().equals(borrower)
                            && p.paymentId().equals(test.paymentId()))
                    .map(PaymentEvent::scheduledDate)
                    .filter(d -> !d.isAfter(periodEnd))
                    .max(LocalDate::compareTo)
                    .orElse(periodEnd);
        }
        return periodEnd;
    }

    private record BorrowerSchedule(String borrower, List<Engine.PeriodKey> periods) {
    }

    private record Key(String borrower, String testId,
                       LocalDate periodStart, LocalDate periodEnd) {
    }

    private static final class TrackedState {
        private Status status = Status.COMPLIANT;
        private LocalDate firstBreachDate;
        private java.math.BigDecimal value;
        private String explanation = "";
        private List<String> evidence = new ArrayList<>();
    }
}
