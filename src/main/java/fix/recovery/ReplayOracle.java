package fix.recovery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Independent naive replay oracle.
 *
 * <p>Re-implements recovery sequencing without the journal/checkpoint
 * machinery: it buffers future messages, processes the in-order prefix,
 * validates that expected sequence numbers advance monotonically, counts
 * applications per business MsgSeqNum (never more than one), and folds the
 * same canonical business lines into an FNV digest. The engine's committed
 * journal and checkpoint must match this oracle on the same input.
 */
public final class ReplayOracle {
    private final FixParser parser = new FixParser();

    public ReplayOracle() {
    }

    public OracleResult replay(Path inboundLog, int firstInSeqNum) {
        try {
            return replay(Files.readAllLines(inboundLog, StandardCharsets.UTF_8), firstInSeqNum);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public OracleResult replay(List<String> lines, int firstInSeqNum) {
        List<Integer> expectedSeq = new ArrayList<>();
        Map<Integer, Integer> applyCounts = new HashMap<>();
        Digest64 digest = new Digest64();
        EventLog events = new EventLog();
        TreeMap<Integer, FixMessage> buffer = new TreeMap<>();
        int expected = firstInSeqNum;
        long lineNo = 0;

        for (String raw : lines) {
            lineNo++;
            ParseOutcome outcome = parser.parse(raw);
            if (!outcome.ok()) {
                if (outcome.code().severity() == Severity.FATAL) {
                    throw new RecoveryAbortedException("oracle line " + lineNo + ": "
                            + outcome.code() + " " + outcome.detail());
                }
                events.record(lineNo, outcome.code(), outcome.detail());
                continue;
            }
            FixMessage msg = outcome.message();
            int seq = msg.seqNum();
            if (seq < expected) {
                if (MsgTypes.SEQUENCE_RESET.equals(msg.msgType())) {
                    throw new RecoveryAbortedException(
                            "oracle rollback line " + lineNo + " seq=" + seq + " expected>=" + expected);
                }
                events.record(lineNo, RejectCode.DUPLICATE_TAG,
                        "oracle replay seq " + seq + " applied at most once");
                continue;
            }
            buffer.putIfAbsent(seq, msg);
            expected = drain(buffer, expected, expectedSeq, applyCounts, digest, events, lineNo);
        }
        if (!buffer.isEmpty()) {
            throw new RecoveryAbortedException(
                    "oracle incomplete: expected " + expected + " buffered " + buffer.keySet());
        }
        for (Map.Entry<Integer, Integer> e : applyCounts.entrySet()) {
            if (e.getValue() > 1) {
                throw new RecoveryAbortedException(
                        "oracle applied seq " + e.getKey() + " " + e.getValue() + " times");
            }
        }
        return new OracleResult(List.copyOf(expectedSeq), Map.copyOf(applyCounts),
                digest.hex(), events.events());
    }

    private int drain(TreeMap<Integer, FixMessage> buffer, int expected, List<Integer> expectedSeq,
                      Map<Integer, Integer> applyCounts, Digest64 digest, EventLog events,
                      long lineNo) {
        while (!buffer.isEmpty() && buffer.firstKey() == expected) {
            FixMessage msg = buffer.pollFirstEntry().getValue();
            expectedSeq.add(expected);
            int prev = expectedSeq.size() >= 2 ? expectedSeq.get(expectedSeq.size() - 2) : expected - 1;
            if (expected < prev + 1 && expected != prev) {
                throw new RecoveryAbortedException("expected sequence not monotonic at " + expected);
            }
            if (MsgTypes.SEQUENCE_RESET.equals(msg.msgType())) {
                int newSeq = Integer.parseInt(msg.tag(FixTags.NEW_SEQ_NO));
                if (newSeq <= expected) {
                    throw new RecoveryAbortedException(
                            "oracle reset rollback line " + lineNo + " new=" + newSeq);
                }
                expected = newSeq;
                continue;
            }
            if (MsgTypes.isBusinessType(msg.msgType())) {
                String canonical = BusinessState.canonicalLine(msg);
                digest.update(canonical);
                applyCounts.merge(expected, 1, Integer::sum);
            }
            expected++;
        }
        return expected;
    }

    private boolean isGapFill(FixMessage msg) {
        return MsgTypes.SEQUENCE_RESET.equals(msg.msgType())
                && "Y".equals(msg.tag(FixTags.GAP_FILL_FLAG));
    }
}
