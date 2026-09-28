package fix.recovery;

/** Fatal condition that must terminate session recovery. */
@SuppressWarnings("serial")
public final class RecoveryAbortedException extends RuntimeException {
    public RecoveryAbortedException(String message) {
        super(message);
    }
}
