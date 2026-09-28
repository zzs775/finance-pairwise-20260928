package fix.recovery.test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import fix.recovery.BusinessJournal;
import fix.recovery.Cli;
import fix.recovery.CrashPoint;

/** CLI end-to-end run and byte-identical recovery comparison. */
public final class CliTest {
    public static void register(TestFramework t) {
        t.run("cli recover finishes with oracle match", () -> {
            try {
                Path work = TestSupport.newWorkDir("cli-ok");
                Path inbound = Fixtures.writeLog(work, "inbound.log", scenario(4));
                int code = Cli.run(new String[] {
                        "recover", "--in", inbound.toString(), "--work", work.toString(), "--oracle"});
                TestFramework.eq(0, code, "exit 0");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        t.run("cli oracle prints conserved digest", () -> {
            try {
                Path work = TestSupport.newWorkDir("cli-oracle");
                Path inbound = Fixtures.writeLog(work, "inbound.log", scenario(3));
                int code = Cli.run(new String[] {"oracle", "--in", inbound.toString()});
                TestFramework.eq(0, code, "exit 0");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        for (CrashPoint point : CrashPoint.values()) {
            t.run("cli crash at " + point + " reruns byte identical", () -> {
                try {
                    Path faultFree = TestSupport.newWorkDir("cli-baseline");
                    List<String> lines = scenario(5);
                    Path inA = Fixtures.writeLog(faultFree, "inbound.log", lines);
                    TestFramework.eq(0, Cli.run(new String[] {
                            "recover", "--in", inA.toString(), "--work",
                            faultFree.resolve("state").toString()}), "baseline run");

                    Path crashed = TestSupport.newWorkDir("cli-crash-" + point);
                    Path inB = Fixtures.writeLog(crashed, "inbound.log", lines);
                    Path stateB = crashed.resolve("state");
                    Cli.run(new String[] {"recover", "--in", inB.toString(),
                            "--work", stateB.toString(), "--crash", point.name()});
                    TestFramework.eq(0, Cli.run(new String[] {"recover", "--in", inB.toString(),
                            "--work", stateB.toString(), "--oracle"}), "recovery rerun");

                    TestFramework.eq(journalText(faultFree.resolve("state")),
                            journalText(stateB), "recovered journal byte identical");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        t.run("cli grades checksum-corrupted input and detects resulting gap", () -> {
            try {
                Path work = TestSupport.newWorkDir("cli-corrupt");
                List<String> lines = new ArrayList<>(scenario(3));
                lines.set(1, lines.get(1).replaceAll("10=\\d{3}$", "10=000"));
                Path inbound = Fixtures.writeLog(work, "inbound.log", lines);
                try {
                    Cli.run(new String[] {
                            "recover", "--in", inbound.toString(), "--work", work.toString()});
                    throw new AssertionError("expected abort on unfilled gap");
                } catch (fix.recovery.RecoveryAbortedException aborted) {
                    TestFramework.check(aborted.getMessage().contains("missing seq"),
                            "gap detected: " + aborted.getMessage());
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static List<String> scenario(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(Fixtures.order(i));
        }
        return out;
    }

    private static String journalText(Path state) throws Exception {
        BusinessJournal journal = new BusinessJournal(state.resolve("business.log"));
        journal.open();
        StringBuilder sb = new StringBuilder();
        for (String line : journal.committedEntries()) {
            sb.append(line).append(System.lineSeparator());
        }
        return sb.toString();
    }
}
