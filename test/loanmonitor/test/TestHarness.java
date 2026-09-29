package loanmonitor.test;

import java.math.BigDecimal;
import java.util.Objects;

/** Minimal zero-dependency assertion harness. */
public final class TestHarness {

    private int passed;
    private int failed;

    public void check(boolean condition, String name) {
        if (condition) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    public void eq(Object actual, Object expected, String name) {
        if (Objects.equals(actual, expected)) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name + " expected=" + expected
                    + " actual=" + actual);
        }
    }

    public void eqBd(BigDecimal actual, String expected, String name) {
        check(actual.compareTo(new BigDecimal(expected)) == 0,
                name + " expected=" + expected + " actual=" + actual);
    }

    public void expectThrows(Class<? extends Throwable> type, Runnable r,
                             String name) {
        try {
            r.run();
            failed++;
            System.out.println("FAIL " + name + " expected " + type.getSimpleName());
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                passed++;
                System.out.println("PASS " + name);
            } else {
                failed++;
                System.out.println("FAIL " + name + " expected " + type.getSimpleName()
                        + " actual " + t.getClass().getSimpleName());
            }
        }
    }

    public int passed() {
        return passed;
    }

    public int failed() {
        return failed;
    }
}
