package fx;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes immutable {@link MarketSnapshot}s atomically.
 * A failed publish never exposes a mix of new spot and old curves.
 */
public final class SnapshotStore {

    private final AtomicReference<MarketSnapshot> current = new AtomicReference<>();

    public SnapshotStore() {
    }

    public MarketSnapshot current() {
        MarketSnapshot snapshot = current.get();
        if (snapshot == null) {
            throw new FxException(Failure.SNAPSHOT_INVALID, "no market snapshot has been published");
        }
        return snapshot;
    }

    public void publish(MarketSnapshot candidate) {
        Objects.requireNonNull(candidate, "candidate");
        // The candidate is already immutable and fully validated by its constructor,
        // so this single reference swap cannot expose new spot alongside old curves.
        current.set(candidate);
    }
}
