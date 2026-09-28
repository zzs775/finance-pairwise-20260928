package fix.recovery;

import java.util.List;

/** Append-only store of locally persisted outbound messages. */
public interface OutboundStore {
    void append(StoredMessage message);

    StoredMessage get(int seqNum);

    int nextSeqNum();

    List<StoredMessage> range(int beginInclusive, int endExclusive);

    void close();
}
