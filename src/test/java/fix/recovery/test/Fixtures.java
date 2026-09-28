package fix.recovery.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import fix.recovery.FixChecks;
import fix.recovery.FixTags;
import fix.recovery.MsgTypes;

/** Local synthetic FIX log builders; all data is fictitious. */
public final class Fixtures {
    public static final String SENDER = "SENDER";
    public static final String TARGET = "TARGET";
    public static final String T0 = "20260101-00:00:00.000";

    private Fixtures() {
    }

    /** Builds a checksummed, length-correct line from ordered tag/value pairs. */
    public static String line(String... tagValues) {
        StringBuilder body = new StringBuilder();
        String type = null;
        for (int i = 0; i < tagValues.length; i += 2) {
            String tag = tagValues[i];
            String value = tagValues[i + 1];
            if (tag.equals(String.valueOf(FixTags.MSG_TYPE))) {
                type = value;
            }
            body.append(tag).append('=').append(value).append(FixChecks.SOH);
        }
        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.US_ASCII);
        String head = FixTags.BEGIN_STRING + "=FIX.4.4" + FixChecks.SOH
                + FixTags.BODY_LENGTH + "=" + bodyBytes.length + FixChecks.SOH;
        byte[] headBody = (head + body).getBytes(StandardCharsets.US_ASCII);
        int sum = FixChecks.checksum(headBody, 0, headBody.length);
        String whole = head + body + FixTags.CHECK_SUM + "=" + FixChecks.formatChecksum(sum);
        return whole.replace(FixChecks.SOH, '|');
    }

    /** Standard business NewOrderSingle with given seq and a fictitious symbol. */
    public static String order(int seq) {
        return line(
                String.valueOf(FixTags.MSG_TYPE), MsgTypes.NEW_ORDER_SINGLE,
                String.valueOf(FixTags.SENDER_COMP_ID), SENDER,
                String.valueOf(FixTags.TARGET_COMP_ID), TARGET,
                String.valueOf(FixTags.SENDING_TIME), T0,
                String.valueOf(FixTags.MSG_SEQ_NUM), String.valueOf(seq),
                "55", "ZZZ", "54", "1", "11", "ORD" + seq);
    }

    public static String execution(int seq) {
        return line(
                String.valueOf(FixTags.MSG_TYPE), MsgTypes.EXECUTION_REPORT,
                String.valueOf(FixTags.SENDER_COMP_ID), SENDER,
                String.valueOf(FixTags.TARGET_COMP_ID), TARGET,
                String.valueOf(FixTags.SENDING_TIME), T0,
                String.valueOf(FixTags.MSG_SEQ_NUM), String.valueOf(seq),
                "55", "ZZZ", "17", "EXEC" + seq);
    }

    public static String heartbeat(int seq) {
        return line(
                String.valueOf(FixTags.MSG_TYPE), MsgTypes.HEARTBEAT,
                String.valueOf(FixTags.SENDER_COMP_ID), SENDER,
                String.valueOf(FixTags.TARGET_COMP_ID), TARGET,
                String.valueOf(FixTags.SENDING_TIME), T0,
                String.valueOf(FixTags.MSG_SEQ_NUM), String.valueOf(seq));
    }

    /** GapFill (123=Y) SequenceReset occupying seq and jumping to newSeqNo. */
    public static String gapFill(int seq, int newSeqNo) {
        return line(
                String.valueOf(FixTags.MSG_TYPE), MsgTypes.SEQUENCE_RESET,
                String.valueOf(FixTags.SENDER_COMP_ID), SENDER,
                String.valueOf(FixTags.TARGET_COMP_ID), TARGET,
                String.valueOf(FixTags.SENDING_TIME), T0,
                String.valueOf(FixTags.MSG_SEQ_NUM), String.valueOf(seq),
                String.valueOf(FixTags.GAP_FILL_FLAG), "Y",
                String.valueOf(FixTags.NEW_SEQ_NO), String.valueOf(newSeqNo));
    }

    /** Forced reset (no 123) occupying seq and jumping to newSeqNo. */
    public static String reset(int seq, int newSeqNo) {
        return line(
                String.valueOf(FixTags.MSG_TYPE), MsgTypes.SEQUENCE_RESET,
                String.valueOf(FixTags.SENDER_COMP_ID), SENDER,
                String.valueOf(FixTags.TARGET_COMP_ID), TARGET,
                String.valueOf(FixTags.SENDING_TIME), T0,
                String.valueOf(FixTags.MSG_SEQ_NUM), String.valueOf(seq),
                String.valueOf(FixTags.NEW_SEQ_NO), String.valueOf(newSeqNo));
    }

    public static String possDupOrder(int seq) {
        return line(
                String.valueOf(FixTags.MSG_TYPE), MsgTypes.NEW_ORDER_SINGLE,
                String.valueOf(FixTags.SENDER_COMP_ID), SENDER,
                String.valueOf(FixTags.TARGET_COMP_ID), TARGET,
                String.valueOf(FixTags.SENDING_TIME), T0,
                String.valueOf(FixTags.MSG_SEQ_NUM), String.valueOf(seq),
                String.valueOf(FixTags.POSS_DUP_FLAG), "Y",
                "55", "ZZZ", "54", "1", "11", "ORD" + seq);
    }

    public static List<String> gapScenario() {
        return new ArrayList<>(List.of(order(1), order(3), order(2), order(4)));
    }

    public static List<String> duplicateScenario() {
        return new ArrayList<>(List.of(order(1), order(1), possDupOrder(1), order(2)));
    }

    public static List<String> reorderScenario() {
        return new ArrayList<>(List.of(order(3), order(1), order(2), order(4)));
    }

    public static Path writeLog(Path dir, String name, List<String> lines) {
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(name);
            Files.write(file, lines, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
