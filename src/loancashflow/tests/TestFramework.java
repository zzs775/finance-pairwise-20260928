package loancashflow.tests;

import java.math.BigDecimal;
import java.util.Objects;

/** Minimal zero-dependency assertion harness. */
public final class TestFramework {

    private int passed;
    private int failed;

    public void check(String name, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    public void eq(String name, Object expected, Object actual) {
        check(name, expected == null ? actual == null : expected.equals(actual));
    }

    public void eqMoney(String name, BigDecimal expected, BigDecimal actual) {
        check(name + " (expected=" + expected + ", actual=" + actual + ")",
                actual != null && expected.compareTo(actual) == 0);
    }

    public void close(String name, BigDecimal expected, BigDecimal actual, BigDecimal tolerance) {
        check(name + " (expected=" + expected + ", actual=" + actual + ")",
                actual != null && expected.subtract(actual).abs().compareTo(tolerance) <= 0);
    }

    public void failsWith(String name, loancashflow.engine.ErrorCode expected, Runnable runnable) {
        try {
            runnable.run();
            failed++;
            System.out.println("FAIL: " + name + " (expected error " + expected + ")");
        } catch (loancashflow.engine.LoanException exception) {
            if (Objects.equals(expected, exception.code())) {
                passed++;
            } else {
                failed++;
                System.out.println("FAIL: " + name + " (expected " + expected
                        + " but got " + exception.code() + ": " + exception.getMessage() + ")");
            }
        } catch (RuntimeException exception) {
            failed++;
            System.out.println("FAIL: " + name + " (unexpected "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage() + ")");
        }
    }

    public int passed() {
        return passed;
    }

    public int failed() {
        return failed;
    }

    public void report() {
        System.out.println();
        System.out.println("Tests: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            throw new AssertionError(failed + " test(s) failed");
        }
    }
}
