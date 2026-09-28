package fix.recovery;

import java.util.List;

/** Drives the journal+checkpoint two-phase commit around one apply batch. */
public final class CommitCoordinator {
    private final BusinessJournal journal;
    private final CheckpointFile checkpointFile;

    public CommitCoordinator(BusinessJournal journal, CheckpointFile checkpointFile) {
        this.journal = journal;
        this.checkpointFile = checkpointFile;
    }

    /** Commits a single canonical business line and its checkpoint. */
    public void commitOne(String canonical, CommitRecord record, SessionSnapshot after,
                          java.util.function.Consumer<CrashPoint> crash) {
        journal.appendBatch(List.of(canonical));
        crash.accept(CrashPoint.AFTER_JOURNAL_APPEND);
        journal.prepare();
        crash.accept(CrashPoint.AFTER_JOURNAL_PREPARE);
        checkpointFile.write(after);
        crash.accept(CrashPoint.AFTER_CHECKPOINT_WRITE);
        journal.commit(record);
        crash.accept(CrashPoint.AFTER_JOURNAL_COMMIT);
    }
}
