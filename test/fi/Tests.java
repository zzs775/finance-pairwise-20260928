package fi;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Dependency-free test harness. Test classes register named checks; the
 * harness counts and reports failures and exits non-zero on any failure.
 */
public final class Tests {

    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    private Tests() {
    }

    public static void main(String[] args) {
        run("frequency", FrequencyTest::run);
        run("daycount", DayCountTest::run);
        run("calendar", CalendarTest::run);
        run("validation", ValidationTest::run);
        run("stubs", StubTest::run);
        run("schedule", ScheduleTest::run);
        run("amortization", AmortizationTest::run);
        run("exdividend", ExDividendTest::run);
        run("oracle", OracleTest::run);
        run("codec", CodecTest::run);

        System.out.println("checks=" + checks + " failures=" + FAILURES.size());
        if (!FAILURES.isEmpty()) {
            for (String failure : FAILURES) {
                System.out.println("FAIL " + failure);
            }
            System.exit(1);
        }
        System.out.println("ALL TESTS PASSED");
    }

    private static void run(String name, Consumer<Asserts> body) {
        Asserts asserts = new Asserts(name);
        try {
            body.accept(asserts);
        } catch (RuntimeException ex) {
            FAILURES.add(name + ": uncaught " + ex);
        }
    }

    static void check(String label, boolean condition) {
        checks++;
        if (!condition) {
            FAILURES.add(label);
        }
    }

    static void eq(String label, Object actual, Object expected) {
        check(label + " expected=" + expected + " actual=" + actual,
                java.util.Objects.equals(actual, expected));
    }

    static void eqMoney(String label, BigDecimal actual, String expected) {
        eq(label, actual.stripTrailingZeros().toPlainString(),
                new BigDecimal(expected).stripTrailingZeros().toPlainString());
    }

    static void expectThrows(String label, ErrorCode expected, Runnable action) {
        try {
            action.run();
            FAILURES.add(label + ": expected " + expected + " but nothing thrown");
        } catch (ScheduleException ex) {
            check(label + " code expected=" + expected + " actual=" + ex.code(),
                    ex.code() == expected);
        }
        checks++;
    }

    public static final class Asserts {
        private final String suite;

        Asserts(String suite) {
            this.suite = suite;
        }

        public void that(String label, boolean condition) {
            Tests.check(suite + ": " + label, condition);
        }

        public void equal(String label, Object actual, Object expected) {
            Tests.eq(suite + ": " + label, actual, expected);
        }

        public void money(String label, BigDecimal actual, String expected) {
            Tests.eqMoney(suite + ": " + label, actual, expected);
        }

        public void throwsCode(String label, ErrorCode code, Runnable action) {
            Tests.expectThrows(suite + ": " + label, code, action);
        }
    }
}
