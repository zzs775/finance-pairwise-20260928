package fix.recovery.test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import fix.recovery.OracleResult;
import fix.recovery.RecoveryAbortedException;
import fix.recovery.RecoveryResult;
import fix.recovery.ReplayOracle;

/** Oracle cross-check: commutative block shuffle and repeated checkpoint reads. */
public final class OracleTest {
    public static void register(TestFramework t) {
        t.run("engine digest matches naive oracle on clean log", () -> {
            List<String> lines = List.of(Fixtures.order(1), Fixtures.execution(2), Fixtures.order(3));
            RecoveryResult r = TestSupport.runLines("oracle-clean", lines);
            OracleResult o = new ReplayOracle().replay(lines, 1);
            TestFramework.eq(o.digest(), r.snapshot().digest(), "digest conserved");
            TestFramework.eq(3, o.expectedSeq().size(), "all expected seqs seen");
        });

        t.run("oracle tolerates reordering same as engine", () -> {
            List<String> lines = Fixtures.reorderScenario();
            RecoveryResult r = TestSupport.runLines("oracle-reorder", lines);
            OracleResult o = new ReplayOracle().replay(lines, 1);
            TestFramework.eq(o.digest(), r.snapshot().digest(), "digest matches on gap/reorder");
        });

        t.run("shuffling independent business blocks preserves digest", () -> {
            List<String> ordered = List.of(Fixtures.order(1), Fixtures.execution(2),
                    Fixtures.order(3), Fixtures.execution(4));
            List<String> shuffled = List.of(Fixtures.execution(4), Fixtures.order(3),
                    Fixtures.execution(2), Fixtures.order(1));
            OracleResult a = new ReplayOracle().replay(ordered, 1);
            OracleResult b = new ReplayOracle().replay(shuffled, 1);
            TestFramework.eq(a.digest(), b.digest(), "commutative blocks same digest");
            TestFramework.eq(a.applyCounts(), b.applyCounts(), "apply counts identical");
        });

        t.run("each business message applied at most once by oracle", () -> {
            List<String> lines = List.of(Fixtures.order(1), Fixtures.order(1),
                    Fixtures.possDupOrder(1), Fixtures.order(2));
            OracleResult o = new ReplayOracle().replay(lines, 1);
            TestFramework.eq(1, o.applyCounts().get(1), "seq1 once");
            TestFramework.eq(1, o.applyCounts().get(2), "seq2 once");
        });

        t.run("oracle expected sequence is strictly monotonic", () -> {
            List<String> lines = Fixtures.gapScenario();
            OracleResult o = new ReplayOracle().replay(lines, 1);
            int prev = 0;
            for (int seq : o.expectedSeq()) {
                TestFramework.check(seq > prev, "monotonic " + prev + " -> " + seq);
                prev = seq;
            }
        });

        t.run("oracle rejects reset rollback", () -> {
            List<String> lines = List.of(Fixtures.order(1), Fixtures.reset(2, 1));
            TestFramework.fails(() -> new ReplayOracle().replay(lines, 1),
                    RecoveryAbortedException.class, "oracle aborts on rollback");
        });

        t.run("engine and oracle agree after every crash recovery", () -> {
            List<String> lines = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                lines.add(Fixtures.order(i));
            }
            Path work = TestSupport.newWorkDir("oracle-crash");
            Path inbound = Fixtures.writeLog(work, "inbound.log", lines);
            TestSupport.run(work, inbound);
            RecoveryResult r = TestSupport.run(work, inbound);
            OracleResult o = new ReplayOracle().replay(lines, 1);
            TestFramework.eq(o.digest(), r.snapshot().digest(), "post-crash digest conserved");
        });
    }
}
