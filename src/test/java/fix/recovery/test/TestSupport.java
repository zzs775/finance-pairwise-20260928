package fix.recovery.test;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import fix.recovery.CrashPoint;
import fix.recovery.EventLog;
import fix.recovery.MemoryOutboundStore;
import fix.recovery.RecoveryEngine;
import fix.recovery.RecoveryResult;

/** Shared engine invocation helpers for tests. */
final class TestSupport {
    private TestSupport() {
    }

    static Path newWorkDir(String name) {
        Path dir = Path.of("target", "test-work", name + "-" + System.nanoTime());
        dir.toFile().mkdirs();
        return dir;
    }

    static RecoveryResult run(Path work, Path inbound) {
        return run(work, inbound, point -> {
        });
    }

    static RecoveryResult run(Path work, Path inbound, Consumer<CrashPoint> hook) {
        EventLog events = new EventLog();
        MemoryOutboundStore store = new MemoryOutboundStore(1);
        return new RecoveryEngine(work, events, store, hook).run(inbound);
    }

    static RecoveryResult runLines(String name, List<String> lines) {
        Path work = newWorkDir(name);
        Path inbound = Fixtures.writeLog(work, "inbound.log", lines);
        return run(work, inbound);
    }
}
