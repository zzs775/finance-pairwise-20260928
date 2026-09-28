package fix.recovery;

/** Durable session checkpoint state. */
public record SessionSnapshot(long incomingBytes, long incomingLines, int nextInSeqNum,
                              int nextOutSeqNum, long journalSize, long journalEntries,
                              String digest) {
    public static SessionSnapshot initial(int firstInSeqNum, int firstOutSeqNum) {
        return new SessionSnapshot(0L, 0L, firstInSeqNum, firstOutSeqNum, 0L, 0L,
                new Digest64().hex());
    }
}
