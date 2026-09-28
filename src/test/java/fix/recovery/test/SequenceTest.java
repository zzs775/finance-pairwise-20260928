package fix.recovery.test;

import java.util.List;

import fix.recovery.RecoveryAbortedException;
import fix.recovery.RecoveryResult;

/** Gap detection, PossDup dedupe, SequenceReset forward-only, illegal rollback. */
public final class SequenceTest {
    public static void register(TestFramework t) {
        t.run("gap then refill applies all in order", () -> {
            RecoveryResult r = TestSupport.runLines("seq-gap", Fixtures.gapScenario());
            TestFramework.eq(5, r.snapshot().nextInSeqNum(), "consumed through seq 4");
            TestFramework.eq(4, r.applied().size(), "four business applied");
            long resend = r.events().stream()
                    .filter(e -> e.detail() != null && e.detail().contains("RESEND_REQUEST"))
                    .count();
            TestFramework.check(resend >= 1, "resend request emitted for gap: " + resend);
        });

        t.run("duplicate business message applied at most once", () -> {
            RecoveryResult r = TestSupport.runLines("seq-dup", Fixtures.duplicateScenario());
            TestFramework.eq(2, r.applied().size(), "only seq 1 and 2 applied");
            TestFramework.eq(3, r.snapshot().nextInSeqNum(), "next seq 3");
        });

        t.run("out of order delivery buffers and drains", () -> {
            RecoveryResult r = TestSupport.runLines("seq-reorder", Fixtures.reorderScenario());
            TestFramework.eq(List.of("BIZ\t1\tD", "BIZ\t2\tD", "BIZ\t3\tD", "BIZ\t4\tD"),
                    r.applied().stream().map(s -> s.substring(0, 7)).toList(),
                    "canonical order preserved");
            TestFramework.eq(4, r.applied().size(), "four applied");
        });

        t.run("gap fill advances over absent sequence", () -> {
            RecoveryResult r = TestSupport.runLines("seq-gapfill", List.of(
                    Fixtures.order(1), Fixtures.gapFill(2, 4), Fixtures.order(4)));
            TestFramework.eq(5, r.snapshot().nextInSeqNum(), "advanced to 5");
            TestFramework.eq(2, r.applied().size(), "seq 2/3 skipped by gapfill");
        });

        t.run("forward reset skips a hole", () -> {
            RecoveryResult r = TestSupport.runLines("seq-reset", List.of(
                    Fixtures.order(1), Fixtures.reset(2, 5), Fixtures.order(5)));
            TestFramework.eq(6, r.snapshot().nextInSeqNum(), "reset to 5 then 5 consumed");
            TestFramework.eq(2, r.applied().size(), "1 and 5 applied");
        });

        t.run("gap fill covering acknowledged message aborts", () -> {
            TestFramework.fails(() -> TestSupport.runLines("seq-badgap", List.of(
                            Fixtures.order(1), Fixtures.order(2), Fixtures.gapFill(2, 4))),
                    RecoveryAbortedException.class, "gapfill covers applied seq 2");
        });

        t.run("reset rolling backwards aborts", () -> {
            TestFramework.fails(() -> TestSupport.runLines("seq-rollback", List.of(
                            Fixtures.order(1), Fixtures.order(2), Fixtures.reset(3, 2))),
                    RecoveryAbortedException.class, "newSeqNo below current");
        });

        t.run("plain duplicate business without PossDup is graded and applied once", () -> {
            RecoveryResult r = TestSupport.runLines("seq-old", List.of(
                    Fixtures.order(1), Fixtures.order(2), Fixtures.order(2), Fixtures.order(3)));
            TestFramework.eq(3, r.applied().size(), "dup ignored, 1-3 applied");
            TestFramework.check(r.events().stream().anyMatch(e -> e.code() == fix.recovery.RejectCode.DUPLICATE_TAG),
                    "duplicate graded");
        });

        t.run("PossDup replay of applied business is ignored", () -> {
            RecoveryResult r = TestSupport.runLines("seq-possdup", List.of(
                    Fixtures.order(1), Fixtures.order(2), Fixtures.possDupOrder(1),
                    Fixtures.order(3)));
            TestFramework.eq(3, r.applied().size(), "dup not reapplied");
            TestFramework.eq(4, r.snapshot().nextInSeqNum(), "continued to 4");
        });

        t.run("gap fill with already buffered higher message drains correctly", () -> {
            RecoveryResult r = TestSupport.runLines("seq-gapfill-buffered", List.of(
                    Fixtures.order(1), Fixtures.order(5), Fixtures.gapFill(2, 4),
                    Fixtures.order(4)));
            TestFramework.eq(6, r.snapshot().nextInSeqNum(), "1, gapfill 2-3, then 4 and 5");
            TestFramework.eq(3, r.applied().size(), "seq 1,4,5 applied; 2,3 gapfilled");
        });
    }
}
