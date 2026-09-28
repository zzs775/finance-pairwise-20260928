package covenant;

import java.util.List;

public record RestatementResult(Snapshot rebuiltSnapshot,
                                List<Alert> incrementalAlerts,
                                boolean byteIdentical,
                                boolean applied) {
}
