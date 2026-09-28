
package fix.recovery;

import java.util.ArrayList;
import java.util.List;

/** In-memory OutboundStore backed by a sequence-indexed list. */
public final class MemoryOutboundStore implements OutboundStore {
    private final List<StoredMessage> messages = new ArrayList<>();
    private int nextSeqNum;

    public MemoryOutboundStore(int firstSeqNum) {
        if (firstSeqNum < 1) {
            throw new IllegalArgumentException("firstSeqNum must be >= 1");
        }
        this.nextSeqNum = firstSeqNum;
    }

    @Override
    public void append(StoredMessage message) {
        if (message.seqNum() != nextSeqNum) {
            throw new IllegalArgumentException(
                    "seq " + message.seqNum() + " does not extend next " + nextSeqNum);
        }
        messages.add(message);
        nextSeqNum++;
    }

    @Override
    public StoredMessage get(int seqNum) {
        int idx = seqNum - (nextSeqNum - messages.size());
        if (idx < 0 || idx >= messages.size()) {
            return null;
        }
        return messages.get(idx);
    }

    @Override
    public int nextSeqNum() {
        return nextSeqNum;
    }

    @Override
    public List<StoredMessage> range(int beginInclusive, int endExclusive) {
        if (beginInclusive < 1 || endExclusive < beginInclusive || endExclusive > nextSeqNum) {
            throw new IllegalArgumentException(
                    "bad range [" + beginInclusive + "," + endExclusive + ") next=" + nextSeqNum);
        }
        int first = nextSeqNum - messages.size();
        List<StoredMessage> out = new ArrayList<>();
        for (int seq = beginInclusive; seq < endExclusive; seq++) {
            out.add(messages.get(seq - first));
        }
        return out;
    }

    @Override
    public void close() {
    }
}
