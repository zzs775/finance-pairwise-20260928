package fix.recovery.test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import fix.recovery.BusinessJournal;
import fix.recovery.CheckpointFile;
import fix.recovery.CrashException;
import fix.recovery.CrashPoint;
import fix.recovery.EventLog;
import fix.recovery.MemoryOutboundStore;
import fix.recovery.RecoveryEngine;
import fix.recovery.RecoveryResult;
import fix.recovery.SessionSnapshot;

/** Crash injection at every commit point followed by replay idempotency. */
public final class CommitTest {
    public static void register(TestFramework t) {
        for (CrashPoint point : CrashPoint.values()) {
            t.run("crash at " + point + " then replay converges", () -> {
                try {
                    crashAt(t, point);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
            t.run("repeated checkpoint reads do not change result", () -> {
                try {
                    repeatedReads();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
    }

    private static void crashAt(TestFramework t, CrashPoint point) throws Exception {
        List<String> lines = scenario(6);
        Path work = TestSupport.newWorkDir("crash-" + point);
        Path inbound = Fixtures.writeLog(work, "inbound.log", lines);
        byte[] baselineJournal;
        final boolean[] fired = {false};
        try {
            new RecoveryEngine(work, new EventLog(), new MemoryOutboundStore(1),
                    cp -> {
                        if (!fired[0] && cp == point) {
                            fired[0] = true;
                            throw new CrashException(cp);
                        }
                    }).run(inbound);
            throw new AssertionError("crash expected at " + point);
        } catch (CrashException expected) {
            TestFramework.eq(point, expected.point(), "injected point");
        }

        new RecoveryEngine(work, new EventLog(), new MemoryOutboundStore(1)).run(inbound);
        RecoveryResult finalResult = new RecoveryEngine(work, new EventLog(),
                new MemoryOutboundStore(1)).run(inbound);

        Path baselineWork = baselineDir(work.getParent(), lines);
        baselineJournal = journalTextBytes(baselineWork);
        BusinessJournal recoveredJournal = new BusinessJournal(work.resolve("business.log"));
        recoveredJournal.open();
        StringBuilder recovered = new StringBuilder();
        for (String line : recoveredJournal.committedEntries()) {
            recovered.append(line).append(System.lineSeparator());
        }
        TestFramework.eq(new String(baselineJournal, StandardCharsets.UTF_8),
                recovered.toString(), "business journal equals fault-free run");
        TestFramework.eq(6, finalResult.applied().size(), "no lost or duplicate application");
        TestFramework.eq(7, finalResult.snapshot().nextInSeqNum(), "final seq 7");
        TestFramework.eq(Files.readString(baselineWork.resolve("checkpoint.ckp"),
                        StandardCharsets.UTF_8),
                Files.readString(work.resolve("checkpoint.ckp"), StandardCharsets.UTF_8),
                "checkpoint byte identical to fault-free run");
    }

    private static void repeatedReads() throws Exception {
        List<String> lines = scenario(4);
        Path work = TestSupport.newWorkDir("reread");
        Path inbound = Fixtures.writeLog(work, "inbound.log", lines);
        new RecoveryEngine(work, new EventLog(), new MemoryOutboundStore(1)).run(inbound);
        CheckpointFile ckp = new CheckpointFile(work.resolve("checkpoint.ckp"));
        SessionSnapshot first = ckp.read();
        for (int i = 0; i < 5; i++) {
            TestFramework.eq(first, ckp.read(), "checkpoint read is stable");
        }
        RecoveryResult again = new RecoveryEngine(work, new EventLog(), new MemoryOutboundStore(1))
                .run(inbound);
        TestFramework.eq(4, again.applied().size(), "replay adds nothing");
        TestFramework.eq(first.digest(), again.snapshot().digest(), "digest stable");
    }

    private static List<String> scenario(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(Fixtures.order(i));
        }
        return out;
    }

    /** Fault-free run in a fresh dir. */
    private static Path baselineDir(Path base, List<String> lines) throws Exception {
        Path clean = base.resolve("baseline-" + System.nanoTime());
        Files.createDirectories(clean);
        Path inbound = Fixtures.writeLog(clean, "inbound.log", lines);
        new RecoveryEngine(clean, new EventLog(), new MemoryOutboundStore(1)).run(inbound);
        return clean;
    }

    private static byte[] journalTextBytes(Path state) {
        BusinessJournal journal = new BusinessJournal(state.resolve("business.log"));
        journal.open();
        StringBuilder sb = new StringBuilder();
        for (String line : journal.committedEntries()) {
            sb.append(line).append(System.lineSeparator());
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

}
