package fix.recovery;

/** Named fault-injection points used by crash recovery tests. */
public enum CrashPoint {
    AFTER_JOURNAL_APPEND,
    AFTER_JOURNAL_PREPARE,
    AFTER_CHECKPOINT_WRITE,
    AFTER_JOURNAL_COMMIT
}
