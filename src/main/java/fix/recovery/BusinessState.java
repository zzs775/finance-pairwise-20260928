package fix.recovery;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Canonical application of business messages. One FIX business message maps
 * to exactly one canonical journal line, so replay is idempotent and the
 * final journal bytes are independent of chunking or crash timing.
 */
public final class BusinessState {
    private final Set<Integer> applied = new HashSet<>();
    private final Digest64 digest;
    private long count;

    public BusinessState() {
        this(new Digest64(), 0);
    }

    public BusinessState(Digest64 digest, long count) {
        this.digest = digest;
        this.count = count;
    }

    /** Rebuilds state by replaying committed journal lines. */
    public static BusinessState fromJournal(List<String> committedLines) {
        BusinessState state = new BusinessState();
        for (String line : committedLines) {
            int seq = parseSeq(line);
            if (!state.applied.add(seq)) {
                throw new RecoveryAbortedException("journal already contains seq " + seq);
            }
            state.digest.update(line);
            state.count++;
        }
        return state;
    }

    public boolean isApplied(int seq) {
        return applied.contains(seq);
    }

    /** Applies a business message exactly once; returns its canonical journal line. */
    public String apply(FixMessage message) {
        int seq = message.seqNum();
        if (!applied.add(seq)) {
            return null;
        }
        String line = canonicalLine(message);
        digest.update(line);
        count++;
        return line;
    }

    public String digestHex() {
        return digest.hex();
    }

    public long count() {
        return count;
    }

    Digest64 digest() {
        return digest;
    }

    /** Stable per-message line: {@code BIZ\t<seq>\t<type>\t<bodyFnv>}. */
    static String canonicalLine(FixMessage message) {
        long bodyHash = fnv(message.canonicalBody());
        return "BIZ\t" + message.seqNum() + "\t" + message.msgType() + "\t"
                + String.format("%016x", bodyHash);
    }

    static int parseSeq(String canonicalLine) {
        String[] parts = canonicalLine.split("\t", 4);
        if (parts.length != 4 || !"BIZ".equals(parts[0])) {
            throw new RecoveryAbortedException("corrupt journal line: " + canonicalLine);
        }
        return Integer.parseInt(parts[1]);
    }

    private static long fnv(String s) {
        Digest64 d = new Digest64();
        d.update(s);
        return d.value();
    }
}
