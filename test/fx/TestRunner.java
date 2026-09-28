package fx;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;

/** Minimal zero-dependency test harness. */
public final class TestRunner {
    private final List<Case> cases = new ArrayList<>();

    public void check(String name, Runnable body) {
        cases.add(new Case(name, body));
    }

    public int run() {
        int passed = 0;
        List<String> failures = new ArrayList<>();
        for (Case testCase : cases) {
            try {
                testCase.body().run();
                passed++;
                System.out.println("PASS " + testCase.name());
            } catch (AssertionError | RuntimeException ex) {
                failures.add(testCase.name() + " -> " + ex);
                System.out.println("FAIL " + testCase.name() + " -> " + ex.getMessage());
            }
        }
        System.out.printf("%n%d passed, %d failed%n", passed, failures.size());
        for (String failure : failures) {
            System.out.println("  " + failure);
        }
        return failures.isEmpty() ? 0 : 1;
    }

    public static void assertTrue(boolean cond, String message) {
        if (!cond) {
            throw new AssertionError(message);
        }
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " expected=<" + expected + "> actual=<" + actual + ">");
        }
    }

    public static void assertEqualsBd(BigDecimal expected, BigDecimal actual,
                                      String message) {
        if (expected.compareTo(actual) != 0) {
            throw new AssertionError(message + " expected=<" + expected + "> actual=<"
                    + actual + ">");
        }
    }

    public static void assertThrows(Failure expected, Runnable body, String message) {
        try {
            body.run();
        } catch (FxException ex) {
            if (ex.failure() == expected) {
                return;
            }
            throw new AssertionError(message + " expected failure " + expected
                    + " but got " + ex.failure());
        }
        throw new AssertionError(message + " expected failure " + expected
                + " but nothing was thrown");
    }

    private record Case(String name, Runnable body) {
    }
}
