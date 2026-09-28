package fix.recovery;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds canonical resend output for an inbound ResendRequest(35=2).
 * Business messages are replayed with PossDup(43)=Y and OrigSendingTime(122);
 * runs of session/admin messages collapse into GapFill(123=Y) SequenceReset
 * messages. Output never skips a sequence number and never exceeds the store.
 */
public final class ResendPlanner {
    private final OutboundStore store;

    public ResendPlanner(OutboundStore store) {
        this.store = store;
    }

    /** Plans resend output for closed range [beginSeqNo, endSeqNo]; end 0 = to latest. */
    public List<ResendEntry> plan(int beginSeqNo, int endSeqNo) {
        int last = store.nextSeqNum() - 1;
        if (beginSeqNo < 1 || beginSeqNo > last + 1) {
            throw new RecoveryAbortedException(
                    "resend begin out of bounds: begin=" + beginSeqNo + " nextOut=" + store.nextSeqNum());
        }
        int end = endSeqNo == 0 ? last : Math.min(endSeqNo, last);
        if (endSeqNo != 0 && endSeqNo < beginSeqNo) {
            throw new RecoveryAbortedException(
                    "resend end before begin: " + beginSeqNo + ">" + endSeqNo);
        }
        if (beginSeqNo > last) {
            return List.of();
        }
        List<ResendEntry> entries = new ArrayList<>();
        int cursor = beginSeqNo;
        while (cursor <= end) {
            StoredMessage msg = store.get(cursor);
            if (MsgTypes.isSessionType(msg.msgType())) {
                int runStart = cursor;
                int newSeq = cursor;
                while (newSeq <= end && MsgTypes.isSessionType(store.get(newSeq).msgType())) {
                    newSeq++;
                }
                byte[] payload = buildGapFill(newSeq, runStart,
                        store.get(runStart).sendingTime());
                entries.add(new ResendEntry(runStart, MsgTypes.SEQUENCE_RESET, true, payload, null));
                cursor = newSeq;
            } else {
                entries.add(new ResendEntry(msg.seqNum(), msg.msgType(), false,
                        toPossDup(msg), msg.sendingTime()));
                cursor++;
            }
        }
        return entries;
    }

    /** Canonical outbound ResendRequest(35=2) emitted when an inbound gap is seen. */
    public byte[] buildResendRequest(int beginSeqNo, int endSeqNo, int senderSeqNum,
                                     String sendingTime) {
        if (beginSeqNo < 1 || endSeqNo < 0 || (endSeqNo != 0 && endSeqNo < beginSeqNo)) {
            throw new RecoveryAbortedException(
                    "illegal resend request range " + beginSeqNo + "->" + endSeqNo);
        }
        String body = FixTags.MSG_TYPE + "=" + MsgTypes.RESEND_REQUEST
                + FixChecks.SOH + FixTags.SENDER_COMP_ID + "=" + SessionIds.SENDER
                + FixChecks.SOH + FixTags.TARGET_COMP_ID + "=" + SessionIds.TARGET
                + FixChecks.SOH + FixTags.SENDING_TIME + "=" + sendingTime
                + FixChecks.SOH + FixTags.MSG_SEQ_NUM + "=" + senderSeqNum
                + FixChecks.SOH + FixTags.BEGIN_SEQ_NO + "=" + beginSeqNo
                + FixChecks.SOH + FixTags.END_SEQ_NO + "=" + endSeqNo
                + FixChecks.SOH;
        return frame(body);
    }

    /** GapFill(123=Y) SequenceReset(35=4) carrying seqNum and jumping to newSeqNo. */
    public byte[] buildGapFill(int newSeqNo, int senderSeqNum, String sendingTime) {
        if (newSeqNo <= senderSeqNum) {
            throw new RecoveryAbortedException(
                    "gap fill cannot move backwards: new=" + newSeqNo + " seq=" + senderSeqNum);
        }
        String body = FixTags.MSG_TYPE + "=" + MsgTypes.SEQUENCE_RESET
                + FixChecks.SOH + FixTags.SENDER_COMP_ID + "=" + SessionIds.SENDER
                + FixChecks.SOH + FixTags.TARGET_COMP_ID + "=" + SessionIds.TARGET
                + FixChecks.SOH + FixTags.SENDING_TIME + "=" + sendingTime
                + FixChecks.SOH + FixTags.MSG_SEQ_NUM + "=" + senderSeqNum
                + FixChecks.SOH + FixTags.POSS_DUP_FLAG + "=Y"
                + FixChecks.SOH + FixTags.GAP_FILL_FLAG + "=Y"
                + FixChecks.SOH + FixTags.NEW_SEQ_NO + "=" + newSeqNo
                + FixChecks.SOH;
        return frame(body);
    }

    private byte[] toPossDup(StoredMessage msg) {
        String body = new String(msg.body(), StandardCharsets.US_ASCII);
        if (body.contains(FixChecks.SOH + FixTags.POSS_DUP_FLAG + "=Y" + FixChecks.SOH)
                || body.startsWith(FixTags.POSS_DUP_FLAG + "=Y" + FixChecks.SOH)) {
            return frame(body);
        }
        String upgraded = body + FixTags.POSS_DUP_FLAG + "=Y" + FixChecks.SOH
                + FixTags.ORIG_SENDING_TIME + "=" + msg.sendingTime() + FixChecks.SOH;
        return frame(upgraded);
    }

    /** Wraps a body (already SOH delimited, trailing SOH) with 8/9 prefix and 10 trailer. */
    public static byte[] frame(String body) {
        byte[] bodyBytes = body.getBytes(StandardCharsets.US_ASCII);
        String head = FixTags.BEGIN_STRING + "=FIX.4.4" + FixChecks.SOH
                + FixTags.BODY_LENGTH + "=" + bodyBytes.length + FixChecks.SOH;
        byte[] headBody = (head + body).getBytes(StandardCharsets.US_ASCII);
        int sum = FixChecks.checksum(headBody, 0, headBody.length);
        String tail = FixTags.CHECK_SUM + "=" + FixChecks.formatChecksum(sum);
        return (head + body + tail + FixChecks.SOH).getBytes(StandardCharsets.US_ASCII);
    }
}
