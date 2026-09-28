
package fix.recovery;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parses one synthetic log line and validates the fixed field subset. */
public final class FixParser {
    private static final Set<Integer> REQUIRED = Set.of(
            FixTags.BEGIN_STRING, FixTags.BODY_LENGTH, FixTags.MSG_TYPE,
            FixTags.SENDER_COMP_ID, FixTags.TARGET_COMP_ID, FixTags.SENDING_TIME,
            FixTags.MSG_SEQ_NUM, FixTags.CHECK_SUM);

    private static final Set<String> KNOWN_TYPES = Set.of(
            MsgTypes.HEARTBEAT, MsgTypes.TEST_REQUEST, MsgTypes.RESEND_REQUEST,
            MsgTypes.REJECT, MsgTypes.SEQUENCE_RESET, MsgTypes.LOGOUT, MsgTypes.LOGON,
            MsgTypes.NEW_ORDER_SINGLE, MsgTypes.EXECUTION_REPORT);

    private static final String FIX_44 = "FIX.4.4";

    public FixParser() {
    }

    public ParseOutcome parse(String line) {
        if (line == null || line.isEmpty()) {
            return ParseOutcome.reject(RejectCode.EMPTY_LINE, "empty line");
        }
        char delimiter = detectDelimiter(line);
        if (delimiter == '?' || !line.startsWith(FixChecks.tagPrefix(FixTags.BEGIN_STRING))) {
            return ParseOutcome.reject(RejectCode.TRUNCATED_LINE, "missing BeginString prefix");
        }
        String[] parts = line.split(delimiter == '|' ? "\\|" : "\u0001", -1);
        if (parts.length == 0 || parts[0].isEmpty()) {
            return ParseOutcome.reject(RejectCode.TRUNCATED_LINE, "line empty");
        }
        int fieldCount = parts.length;
        if (parts[parts.length - 1].isEmpty()) {
            fieldCount = parts.length - 1;
        }
        String[] fields = parts;

        Map<Integer, String> fieldMap = new LinkedHashMap<>();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < fieldCount; i++) {
            String part = fields[i];
            int eq = part.indexOf('=');
            if (eq <= 0) {
                return ParseOutcome.reject(RejectCode.MALFORMED_FIELD,
                        "field without tag=value: '" + part + "'");
            }
            int tag;
            try {
                tag = Integer.parseInt(part.substring(0, eq));
            } catch (NumberFormatException e) {
                return ParseOutcome.reject(RejectCode.MALFORMED_FIELD, "non-numeric tag: " + part);
            }
            if (tag <= 0) {
                return ParseOutcome.reject(RejectCode.MALFORMED_FIELD, "tag out of range: " + tag);
            }
            String value = part.substring(eq + 1);
            if (fieldMap.containsKey(tag)) {
                return ParseOutcome.reject(RejectCode.DUPLICATE_TAG, "tag " + tag + " repeated");
            }
            fieldMap.put(tag, value);
            order.add(tag);
        }

        for (int required : REQUIRED) {
            if (!fieldMap.containsKey(required)) {
                return ParseOutcome.reject(RejectCode.MISSING_REQUIRED_TAG,
                        "missing tag " + required);
            }
        }
        if (!FIX_44.equals(fieldMap.get(FixTags.BEGIN_STRING))) {
            return ParseOutcome.reject(RejectCode.MALFORMED_FIELD,
                    "unsupported BeginString: " + fieldMap.get(FixTags.BEGIN_STRING));
        }
        if (order.get(0) != FixTags.BEGIN_STRING
                || order.get(1) != FixTags.BODY_LENGTH
                || order.get(order.size() - 1) != FixTags.CHECK_SUM) {
            return ParseOutcome.reject(RejectCode.TRUNCATED_LINE,
                    "required field ordering 8,9,...,10 violated");
        }

        byte[] raw = normalize(stripTrailingDelimiter(line, delimiter), delimiter);
        ParseOutcome lengthCheck = checkBodyLength(raw, fieldMap);
        if (lengthCheck != null) {
            return lengthCheck;
        }
        ParseOutcome checksumCheck = checkChecksum(raw, fieldMap);
        if (checksumCheck != null) {
            return checksumCheck;
        }
        ParseOutcome seqCheck = checkSeq(fieldMap);
        if (seqCheck != null) {
            return seqCheck;
        }

        String type = fieldMap.get(FixTags.MSG_TYPE);
        if (!KNOWN_TYPES.contains(type)) {
            return ParseOutcome.reject(RejectCode.INVALID_MSG_TYPE, "MsgType " + type);
        }
        try {
            FixTime.parseEpochMillis(fieldMap.get(FixTags.SENDING_TIME));
        } catch (IllegalArgumentException e) {
            return ParseOutcome.reject(RejectCode.INVALID_TIME, e.getMessage());
        }
        return ParseOutcome.valid(new FixMessage(fieldMap, order, raw, FixChecks.SOH));
    }

    private String stripTrailingDelimiter(String line, char delimiter) {
        if (!line.isEmpty() && line.charAt(line.length() - 1) == delimiter) {
            return line.substring(0, line.length() - 1);
        }
        return line;
    }

    private char detectDelimiter(String line) {
        if (line.indexOf(FixChecks.SOH) >= 0) {
            return FixChecks.SOH;
        }
        if (line.indexOf('|') >= 0) {
            return '|';
        }
        return '?';
    }

    private byte[] normalize(String line, char delimiter) {
        String normalized = delimiter == FixChecks.SOH ? line : line.replace('|', FixChecks.SOH);
        return normalized.getBytes(StandardCharsets.US_ASCII);
    }

    private ParseOutcome checkBodyLength(byte[] raw, Map<Integer, String> fields) {
        int declared;
        try {
            declared = Integer.parseInt(fields.get(FixTags.BODY_LENGTH));
        } catch (NumberFormatException e) {
            return ParseOutcome.reject(RejectCode.INVALID_BODY_LENGTH, "BodyLength not numeric");
        }
        if (declared < 0) {
            return ParseOutcome.reject(RejectCode.INVALID_BODY_LENGTH, "negative BodyLength");
        }
        int actual = FixChecks.actualBodyLength(raw);
        if (actual != declared) {
            return ParseOutcome.reject(RejectCode.INVALID_BODY_LENGTH,
                    "declared=" + declared + " actual=" + actual);
        }
        return null;
    }

    private ParseOutcome checkChecksum(byte[] raw, Map<Integer, String> fields) {
        String declaredText = fields.get(FixTags.CHECK_SUM);
        if (!declaredText.matches("\\d{3}")) {
            return ParseOutcome.reject(RejectCode.INVALID_CHECKSUM,
                    "CheckSum not 3 digits: " + declaredText);
        }
        int declared = Integer.parseInt(declaredText);
        int actual = FixChecks.checksum(raw, 0, raw.length - 6);
        if (actual != declared) {
            return ParseOutcome.reject(RejectCode.INVALID_CHECKSUM,
                    "declared=" + declaredText + " actual=" + FixChecks.formatChecksum(actual));
        }
        return null;
    }

    private ParseOutcome checkSeq(Map<Integer, String> fields) {
        String text = fields.get(FixTags.MSG_SEQ_NUM);
        if (!text.matches("\\d+")) {
            return ParseOutcome.reject(RejectCode.SEQ_NOT_NUMBER, "MsgSeqNum: " + text);
        }
        long value;
        try {
            value = Long.parseLong(text);
        } catch (NumberFormatException e) {
            return ParseOutcome.reject(RejectCode.SEQ_NOT_NUMBER, "MsgSeqNum: " + text);
        }
        if (value <= 0 || value > Integer.MAX_VALUE) {
            return ParseOutcome.reject(RejectCode.SEQ_OUT_OF_RANGE, "MsgSeqNum: " + text);
        }
        return null;
    }
}
