package loanengine;

import java.math.BigDecimal;

public final class TestFramework {

    private static int passed;
    private static int failed;

    private TestFramework() {
    }

    public static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + message);
        }
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual),
                message + " expected=<" + expected + "> actual=<" + actual + ">");
    }

    public static void assertScaledEquals(BigDecimal expected, BigDecimal actual, String message) {
        BigDecimal a = expected.setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal b = actual.setScale(2, java.math.RoundingMode.HALF_UP);
        check(a.compareTo(b) == 0,
                message + " expected=<" + a + "> actual=<" + b + ">");
    }

    public static void assertClose(BigDecimal expected, BigDecimal actual,
                                   BigDecimal tolerance, String message) {
        BigDecimal delta = expected.subtract(actual).abs();
        check(delta.compareTo(tolerance) <= 0,
                message + " expected=~<" + expected + "> actual=<" + actual
                        + "> tol=<" + tolerance + ">");
    }

    public static void summary() {
        System.out.println("PASSED: " + passed + "  FAILED: " + failed);
        if (failed > 0) {
            throw new AssertionError(failed + " test(s) failed");
        }
    }
}
