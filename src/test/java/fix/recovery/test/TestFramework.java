package fix.recovery.test;

import java.util.ArrayList;
import java.util.List;

/** Minimal zero-dependency test framework: named cases, assertions, summary. */
public final class TestFramework {
    public record Failure(String name, String message) {
    }

    private final List<Failure> failures = new ArrayList<>();
    private int passed;
    private String current;

    public void run(String name, Runnable test) {
        current = name;
        try {
            test.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (AssertionError e) {
            failures.add(new Failure(name, e.getMessage()));
            System.out.println("FAIL " + name + " - " + e.getMessage());
        } catch (Throwable t) {
            failures.add(new Failure(name, t.getClass().getSimpleName() + ": " + t.getMessage()));
            System.out.println("FAIL " + name + " - " + t);
        }
    }

    public int report() {
        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failures.size());
        return failures.isEmpty() ? 0 : 1;
    }

    public static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void eq(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + " expected=<" + expected + "> actual=<" + actual + ">");
        }
    }

    public static void fails(Runnable r, Class<?> type, String message) {
        try {
            r.run();
        } catch (RuntimeException e) {
            if (type.isInstance(e)) {
                return;
            }
            throw new AssertionError(message + " expected " + type + " got " + e, e);
        }
        throw new AssertionError(message + " expected " + type + " but nothing thrown");
    }
}
