package csm;

import java.util.Objects;

/** 极简断言助手（仅 JDK）。 */
public final class TestSupport {

    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " expected=<"
                    + expected + "> actual=<" + actual + ">");
        }
    }

    public static void assertThrows(Class<? extends Throwable> type, Runnable body, String message) {
        try {
            body.run();
        } catch (Throwable thrown) {
            if (type.isInstance(thrown)) {
                return;
            }
            throw new AssertionError(message + " expected "
                    + type.getSimpleName() + " but got "
                    + thrown.getClass().getSimpleName() + ": "
                    + thrown.getMessage(), thrown);
        }
        throw new AssertionError(message + " expected "
                + type.getSimpleName() + " but nothing was thrown");
    }
}
