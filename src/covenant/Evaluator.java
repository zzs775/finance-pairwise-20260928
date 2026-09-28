package covenant;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public final class Evaluator {

    private final FactStore store;
    private final MetricCatalog catalog;
    private final List<CovenantTest> tests;
    private final List<Waiver> waivers;
    private final List<PaymentEvent> payments;
    private final MetricCalculator calculator;

    public Evaluator(FactStore store,
                     MetricCatalog catalog,
                     List<CovenantTest> tests,
                     List<Waiver> waivers,
                     List<PaymentEvent> payments) {
        this.store = store;
        this.catalog = catalog;
        this.tests = List.copyOf(tests);
        this.waivers = List.copyOf(waivers);
        this.payments = List.copyOf(payments);
        this.calculator = new MetricCalculator(store, catalog);
    }

    public TestResult evaluateAt(String borrower,
                                 CovenantTest test,
                                 LocalDate periodStart,
                                 LocalDate periodEnd,
                                 LocalDate asOf) {
        List<String> evidence = new ArrayList<>();
        BigDecimal value = null;
        boolean breach = false;
        String explanation = "";
        try {
            switch (test.kind()) {
                case RATIO -> {
                    ExplainedValue numerator = calculator.calculate(borrower,
                            catalog.get(test.numeratorMetric()), periodStart, periodEnd);
                    ExplainedValue denominator = calculator.calculate(borrower,
                            catalog.get(test.denominatorMetric()), periodStart, periodEnd);
                    if (denominator.value().signum() == 0) {
                        throw new DivisionByZeroException(
                                "ratio denominator is zero for test " + test.id());
                    }
                    value = numerator.value().divide(denominator.value(),
                            Math.max(numerator.value().scale(), denominator.value().scale()) + 6,
                            MetricCalculator.ROUNDING)
                            .setScale(test.unit() == null ? 6 : Math.max(6, 2),
                                    MetricCalculator.ROUNDING);
                    breach = value.compareTo(test.threshold()) < 0;
                    explanation = "RATIO " + test.numeratorMetric() + "/" + test.denominatorMetric()
                            + " = " + value.toPlainString() + " >= "
                            + test.threshold().toPlainString();
                    if (breach) {
                        evidence.add(numerator.formulaTree());
                        evidence.add(denominator.formulaTree());
                    }
                }
                case MINIMUM -> {
                    ExplainedValue explained = calculator.calculate(borrower,
                            catalog.get(test.metricId()), periodStart, periodEnd);
                    value = explained.value();
                    breach = value.compareTo(test.threshold()) < 0;
                    explanation = "MINIMUM " + test.metricId() + " = " + value.toPlainString()
                            + " >= " + test.threshold().toPlainString();
                    if (breach) {
                        evidence.add(explained.formulaTree());
                    }
                }
                case MAXIMUM -> {
                    ExplainedValue explained = calculator.calculate(borrower,
                            catalog.get(test.metricId()), periodStart, periodEnd);
                    value = explained.value();
                    breach = value.compareTo(test.threshold()) > 0;
                    explanation = "MAXIMUM " + test.metricId() + " = " + value.toPlainString()
                            + " <= " + test.threshold().toPlainString();
                    if (breach) {
                        evidence.add(explained.formulaTree());
                    }
                }
                case PAYMENT_DATE -> {
                    Optional<PaymentEvent> payment = payments.stream()
                            .filter(p -> p.borrower().equals(borrower)
                                    && p.paymentId().equals(test.paymentId()))
                            .max(java.util.Comparator
                                    .comparing(PaymentEvent::scheduledDate));
                    if (payment.isEmpty()) {
                        if (!test.breachIfMissing()) {
                            value = null;
                            breach = false;
                            explanation = "PAYMENT_DATE no scheduled payment "
                                    + test.paymentId() + " (ignored)";
                            break;
                        }
                        throw new MissingDefinitionException(
                                "payment event missing: " + test.paymentId());
                    }
                    PaymentEvent event = payment.get();
                    value = event.actualDate() == null ? null : BigDecimal.ZERO;
                    LocalDate reference = event.actualDate() == null
                            ? asOf : event.actualDate();
                    breach = reference.isAfter(event.scheduledDate());
                    explanation = "PAYMENT_DATE " + test.paymentId() + " scheduled="
                            + event.scheduledDate() + " actual="
                            + (event.actualDate() == null ? "UNPAID(asof=" + asOf + ")"
                                    : event.actualDate());
                    if (breach) {
                        evidence.add(explanation);
                    }
                }
                default -> throw new InvalidInputException(
                        "unknown test kind: " + test.kind());
            }
        } catch (MissingDefinitionException e) {
            if (!test.breachIfMissing()) {
                return new TestResult(borrower, test.id(), periodStart, periodEnd,
                        Status.COMPLIANT, null,
                        "MISSING(ignored): " + e.getMessage(), List.of(), null, asOf);
            }
            evidence.add("MISSING FACT: " + e.getMessage());
            return new TestResult(borrower, test.id(), periodStart, periodEnd,
                    Status.BREACHED, null, e.getMessage(), List.copyOf(evidence),
                    periodEnd, asOf);
        }
        boolean waived = breach && isWaived(borrower, test.id(), periodStart, periodEnd, asOf);
        Status rawStatus = breach ? Status.BREACHED : Status.COMPLIANT;
        return new TestResult(borrower, test.id(), periodStart, periodEnd,
                waived ? Status.WAIVED : rawStatus,
                value, explanation, List.copyOf(evidence),
                breach ? periodEnd : null, asOf);
    }

    public boolean isWaived(String borrower, String testId,
                            LocalDate periodStart, LocalDate periodEnd, LocalDate asOf) {
        return waivers.stream().anyMatch(w -> w.borrower().equals(borrower)
                && w.testId().equals(testId)
                && Periods.contains(w.periodStart(), w.periodEnd(), periodStart, periodEnd)
                && !w.grantedDate().isAfter(asOf)
                && w.expiresDate().isAfter(asOf)
                && (w.expiresDate().isAfter(periodEnd)));
    }

    public List<CovenantTest> tests() {
        return tests;
    }

    public MetricCatalog catalog() {
        return catalog;
    }

    public FactStore store() {
        return store;
    }

    public List<PaymentEvent> payments() {
        return payments;
    }

    public List<Waiver> waivers() {
        return waivers;
    }

    public Optional<CovenantTest> test(String id) {
        return tests.stream().filter(t -> t.id().equals(id)).findFirst();
    }
}
