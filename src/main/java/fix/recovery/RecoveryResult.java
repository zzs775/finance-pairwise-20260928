package fix.recovery;

import java.util.List;

/** Final engine outcome: checkpoints, applied business lines, events and digest. */
public record RecoveryResult(SessionSnapshot snapshot, List<String> applied, List<LogEvent> events) {
}
