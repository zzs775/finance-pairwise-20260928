package fix.recovery;

/** Synthetic crash used by tests; simulates abrupt process termination. */
@SuppressWarnings("serial")
public final class CrashException extends RuntimeException {
    private final CrashPoint point;

    public CrashException(CrashPoint point) {
        super("injected crash at " + point);
        this.point = point;
    }

    public CrashPoint point() {
        return point;
    }
}
