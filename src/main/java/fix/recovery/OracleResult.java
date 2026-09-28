package fix.recovery;

import java.util.List;
import java.util.Map;

/** Expected sequence progression, per-msg apply counts and conserved digest. */
public record OracleResult(List<Integer> expectedSeq, Map<Integer, Integer> applyCounts,
                           String digest, List<LogEvent> events) {
}
