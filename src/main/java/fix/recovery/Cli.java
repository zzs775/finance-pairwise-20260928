package fix.recovery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Command line entry point for the offline FIX recovery engine.
 *
 * <pre>
 * fix-recovery recover --in &lt;inbound.log&gt; --work &lt;workdir&gt;
 *                      [--crash POINT] [--oracle] [--first-seq N]
 * fix-recovery oracle  --in &lt;inbound.log&gt; [--first-seq N]
 * </pre>
 */
public final class Cli {
    private Cli() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (RecoveryAbortedException e) {
            System.err.println("RECOVERY_ABORTED: " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("ERROR: " + e);
            System.exit(3);
        }
    }

    public static int run(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return 64;
        }
        Map<String, String> opts = parseArgs(args);
        String command = args[0];
        int firstSeq = Integer.parseInt(opts.getOrDefault("first-seq", "1"));
        Path inbound = Path.of(opts.getOrDefault("in", ""));
        switch (command) {
            case "recover" -> {
                Path work = Path.of(opts.getOrDefault("work", "target/run"));
                EventLog events = new EventLog(System.out);
                MemoryOutboundStore store = new MemoryOutboundStore(firstSeq);
                java.util.function.Consumer<CrashPoint> hook = point -> {
                };
                if (opts.containsKey("crash")) {
                    CrashPoint chosen = CrashPoint.valueOf(opts.get("crash"));
                    hook = point -> {
                        if (point == chosen) {
                            throw new CrashException(point);
                        }
                    };
                }
                RecoveryEngine engine = new RecoveryEngine(work, events, store, hook);
                RecoveryResult result;
                try {
                    result = engine.run(inbound);
                } catch (CrashException e) {
                    System.out.println("INJECTED_CRASH " + e.point());
                    return resultAfterCrash(work);
                }
                printResult(result);
                if (opts.containsKey("oracle")) {
                    OracleResult oracle = new ReplayOracle().replay(inbound, firstSeq);
                    checkOracle(result, oracle);
                }
                return 0;
            }
            case "oracle" -> {
                if (!Files.isRegularFile(inbound)) {
                    throw new IllegalArgumentException("missing --in file");
                }
                OracleResult oracle = new ReplayOracle().replay(inbound, firstSeq);
                System.out.println("digest=" + oracle.digest());
                System.out.println("applied=" + oracle.applyCounts().size());
                System.out.println("events=" + oracle.events().size());
                return 0;
            }
            default -> {
                usage();
                return 64;
            }
        }
    }

    private static int resultAfterCrash(Path work) {
        CheckpointFile ckp = new CheckpointFile(work.resolve("checkpoint.ckp"));
        SessionSnapshot snapshot = ckp.exists() ? ckp.read() : null;
        BusinessJournal journal = new BusinessJournal(work.resolve("business.log"));
        journal.open();
        System.out.println("committedEntries=" + journal.committedCount());
        System.out.println("checkpoint=" + (snapshot == null ? "absent" : snapshot.nextInSeqNum()));
        return 0;
    }

    private static void printResult(RecoveryResult result) {
        SessionSnapshot s = result.snapshot();
        System.out.println("nextInSeqNum=" + s.nextInSeqNum());
        System.out.println("nextOutSeqNum=" + s.nextOutSeqNum());
        System.out.println("applied=" + result.applied().size());
        System.out.println("digest=" + s.digest());
        System.out.println("events=" + result.events().size());
    }

    private static void checkOracle(RecoveryResult result, OracleResult oracle) {
        if (!result.snapshot().digest().equals(oracle.digest())) {
            throw new RecoveryAbortedException("digest mismatch engine="
                    + result.snapshot().digest() + " oracle=" + oracle.digest());
        }
        if (result.applied().size() != oracle.applyCounts().size()) {
            throw new RecoveryAbortedException("applied count mismatch");
        }
        System.out.println("ORACLE_MATCH " + oracle.digest());
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--") && i + 1 < args.length) {
                opts.put(a.substring(2), args[++i]);
            }
        }
        return opts;
    }

    private static void usage() {
        System.err.println("usage: fix-recovery {recover|oracle} --in <log> [--work <dir>] "
                + "[--crash POINT] [--oracle] [--first-seq N]");
    }
}
