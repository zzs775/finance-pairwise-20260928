package fix.recovery;

/**
 * Durable two-phase commit decision appended after each applied batch.
 * It records source log position so checkpoints can be rebuilt purely from
 * the authoritative journal:
 * {@code n=<batchSize> line=<endLineNo> bytes=<consumedBytes> next=<nextInSeqNum>}.
 */
public record CommitRecord(int batchSize, long endLine, long bytes, int nextInSeqNum) {
    public String encode() {
        return "n=" + batchSize + " line=" + endLine + " bytes=" + bytes
                + " next=" + nextInSeqNum;
    }

    public static CommitRecord parse(String text) {
        int n = -1;
        long line = 0;
        long bytes = 0;
        int next = 1;
        for (String token : text.strip().split("\\s+")) {
            String[] kv = token.split("=", 2);
            if (kv.length != 2) {
                throw new RecoveryAbortedException("bad COMMIT token: " + token);
            }
            switch (kv[0]) {
                case "n" -> n = Integer.parseInt(kv[1]);
                case "line" -> line = Long.parseLong(kv[1]);
                case "bytes" -> bytes = Long.parseLong(kv[1]);
                case "next" -> next = Integer.parseInt(kv[1]);
                default -> throw new RecoveryAbortedException("unknown COMMIT key: " + kv[0]);
            }
        }
        if (n < 0) {
            throw new RecoveryAbortedException("COMMIT missing batch size");
        }
        return new CommitRecord(n, line, bytes, next);
    }
}
