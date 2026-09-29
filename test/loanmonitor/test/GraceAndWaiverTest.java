package loanmonitor.test;

import java.time.LocalDate;
import java.util.List;

import loanmonitor.DailyOracle;
import loanmonitor.MonitoringEngine;
import loanmonitor.model.BreachKey;
import loanmonitor.model.CovenantDefinition;
import loanmonitor.model.MetricSpec;
import loanmonitor.model.PaymentEvent;
import loanmonitor.model.Status;
import loanmonitor.model.TestPeriod;
import loanmonitor.model.TestType;
import loanmonitor.model.Waiver;

/** Grace-period expiry, cure within grace, partial waiver scoping. */
public final class GraceAndWaiverTest {

    private GraceAndWaiverTest() {
    }

    public static void run(TestHarness h) {
        cureWithinGraceReturnsToCompliant(h);
        breachMovesToCurePendingNextDay(h);
        graceExpiryDefaults(h);
        waiverCoversSpecificPeriodOnly(h);
        waiverDoesNotCoverOtherCovenant(h);
        waiverKeepsBreachEvidence(h);
        waiverAppliedMidGrace(h);
    }

    private static CovenantDefinition paymentCovenant(String id, int graceDays) {
        return new CovenantDefinition(id, Fixtures.BORROWER,
                TestType.PAYMENT_DATE, null,
                new java.math.BigDecimal("0"), graceDays);
    }

    private static TestPeriod period(String label, String end) {
        return Fixtures.period(label, "2024-01-01", end);
    }

    private static MonitoringEngine paymentEngine(String covenantId, int graceDays,
                                                  PaymentEvent payment,
                                                  Waiver waiver,
                                                  TestPeriod period) {
        List<Waiver> waivers = waiver == null ? List.of() : List.of(waiver);
        return Fixtures.engine(List.of(period),
                List.of(paymentCovenant(covenantId, graceDays)),
                List.of(), List.of(payment), waivers);
    }

    private static void cureWithinGraceReturnsToCompliant(TestHarness h) {
        BreachKey key = new BreachKey("PAY1", "Q1");
        PaymentEvent payment = Fixtures.payment("PMT1", "PAY1", "100.00",
                "2024-03-31", "2024-04-03");
        MonitoringEngine engine = paymentEngine("PAY1", 5, payment, null,
                period("Q1", "2024-03-31"));
        DailyOracle oracle = new DailyOracle(engine);
        oracle.runThrough(LocalDate.of(2024, 3, 31), LocalDate.of(2024, 4, 3));
        h.eq(engine.statusOf(key), Status.COMPLIANT,
                "payment within 5-day grace cures back to compliant");
        h.check(!engine.alerts().isEmpty()
                        && engine.alerts().stream().anyMatch(
                        a -> a.type() == loanmonitor.model.AlertType.CURED),
                "cure alert emitted with the cure date");
    }

    private static void breachMovesToCurePendingNextDay(TestHarness h) {
        BreachKey key = new BreachKey("PAY2", "Q1");
        PaymentEvent payment = Fixtures.payment("PMT2", "PAY2", "100.00",
                "2024-03-31", null);
        MonitoringEngine engine = paymentEngine("PAY2", 5, payment, null,
                period("Q1", "2024-03-31"));
        DailyOracle oracle = new DailyOracle(engine);
        oracle.runThrough(LocalDate.of(2024, 3, 31), LocalDate.of(2024, 4, 1));
        h.eq(engine.statusOf(key), Status.CURE_PENDING,
                "day after breach enters cure_pending");
    }

    private static void graceExpiryDefaults(TestHarness h) {
        BreachKey key = new BreachKey("PAY3", "Q1");
        PaymentEvent payment = Fixtures.payment("PMT3", "PAY3", "100.00",
                "2024-03-31", null);
        MonitoringEngine engine = paymentEngine("PAY3", 5, payment, null,
                period("Q1", "2024-03-31"));
        DailyOracle oracle = new DailyOracle(engine);
        oracle.runThrough(LocalDate.of(2024, 3, 31), LocalDate.of(2024, 4, 6));
        h.eq(engine.statusOf(key), Status.DEFAULTED,
                "uncured after deadline breach+5 defaults on day 6");
        var defaultAlert = engine.alerts().stream()
                .filter(a -> a.type() == loanmonitor.model.AlertType.DEFAULTED)
                .findFirst();
        h.check(defaultAlert.isPresent()
                        && defaultAlert.get().date().equals(LocalDate.of(2024, 4, 6)),
                "default alert dated the day after grace deadline");
    }

    private static void waiverCoversSpecificPeriodOnly(TestHarness h) {
        Waiver waiver = Fixtures.waiver("W1", "PAY4", List.of("Q1"),
                "2024-03-31", "2024-12-31");
        PaymentEvent payment = Fixtures.payment("PMT4", "PAY4", "100.00",
                "2024-03-31", null);
        MonitoringEngine engine = paymentEngine("PAY4", 5, payment, waiver,
                period("Q1", "2024-03-31"));
        new DailyOracle(engine).runThrough(LocalDate.of(2024, 3, 31),
                LocalDate.of(2024, 4, 10));
        h.eq(engine.statusOf(new BreachKey("PAY4", "Q1")), Status.WAIVED,
                "breach in the named waived period becomes WAIVED");

        Waiver scoped = Fixtures.waiver("W2", "PAY4B", List.of("Q2"),
                "2024-01-01", "2024-12-31");
        PaymentEvent payment2 = Fixtures.payment("PMT4B", "PAY4B", "100.00",
                "2024-03-31", null);
        MonitoringEngine engine2 = paymentEngine("PAY4B", 5, payment2, scoped,
                period("Q1", "2024-03-31"));
        new DailyOracle(engine2).runThrough(LocalDate.of(2024, 3, 31),
                LocalDate.of(2024, 4, 10));
        h.eq(engine2.statusOf(new BreachKey("PAY4B", "Q1")), Status.DEFAULTED,
                "waiver for Q2 does not cover the Q1 breach");
    }

    private static void waiverDoesNotCoverOtherCovenant(TestHarness h) {
        Waiver waiver = Fixtures.waiver("W3", "OTHER", List.of("Q1"),
                "2024-01-01", "2024-12-31");
        PaymentEvent payment = Fixtures.payment("PMT5", "PAY5", "100.00",
                "2024-03-31", null);
        MonitoringEngine engine = paymentEngine("PAY5", 5, payment, waiver,
                period("Q1", "2024-03-31"));
        new DailyOracle(engine).runThrough(LocalDate.of(2024, 3, 31),
                LocalDate.of(2024, 4, 10));
        h.eq(engine.statusOf(new BreachKey("PAY5", "Q1")), Status.DEFAULTED,
                "waiver scoped to another covenant does not apply");
    }

    private static void waiverKeepsBreachEvidence(TestHarness h) {
        Waiver waiver = Fixtures.waiver("W4", "PAY6", List.of("Q1"),
                "2024-03-31", "2024-12-31");
        PaymentEvent payment = Fixtures.payment("PMT6", "PAY6", "100.00",
                "2024-03-31", null);
        MonitoringEngine engine = paymentEngine("PAY6", 5, payment, waiver,
                period("Q1", "2024-03-31"));
        new DailyOracle(engine).runThrough(LocalDate.of(2024, 3, 31),
                LocalDate.of(2024, 4, 10));
        var snapshot = engine.currentSnapshot();
        h.eq(snapshot.breaches().size(), 1,
                "original breach evidence retained after waiver");
        h.check(snapshot.breaches().get(0).waived(),
                "retained breach record marked waived with waiver id");
        h.eq(snapshot.breaches().get(0).waiverId(), "W4",
                "waiver id attached to retained evidence");
    }

    private static void waiverAppliedMidGrace(TestHarness h) {
        Waiver waiver = Fixtures.waiver("W5", "PAY7", List.of("Q1"),
                "2024-04-02", "2024-12-31");
        PaymentEvent payment = Fixtures.payment("PMT7", "PAY7", "100.00",
                "2024-03-31", null);
        MonitoringEngine engine = paymentEngine("PAY7", 5, payment, waiver,
                period("Q1", "2024-03-31"));
        new DailyOracle(engine).runThrough(LocalDate.of(2024, 3, 31),
                LocalDate.of(2024, 4, 10));
        h.eq(engine.statusOf(new BreachKey("PAY7", "Q1")), Status.WAIVED,
                "waiver becoming effective during cure pending applies mid-grace");
    }
}
