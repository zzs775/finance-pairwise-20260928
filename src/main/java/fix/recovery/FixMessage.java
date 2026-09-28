package fix.recovery;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** An immutable parsed FIX message (tag subset plus raw body bytes). */
public final class FixMessage {
    private final Map<Integer, String> fields;
    private final List<Integer> order;
    private final byte[] raw;
    private final char delimiter;

    public FixMessage(Map<Integer, String> fields, List<Integer> order, byte[] raw, char delimiter) {
        this.fields = Map.copyOf(fields);
        this.order = List.copyOf(order);
        this.raw = raw.clone();
        this.delimiter = delimiter;
    }

    public String tag(int tag) {
        return fields.get(tag);
    }

    public boolean has(int tag) {
        return fields.containsKey(tag);
    }

    public String msgType() {
        return tag(FixTags.MSG_TYPE);
    }

    public int seqNum() {
        return Integer.parseInt(tag(FixTags.MSG_SEQ_NUM));
    }

    public boolean possDup() {
        return "Y".equals(tag(FixTags.POSS_DUP_FLAG));
    }

    public String sendingTime() {
        return tag(FixTags.SENDING_TIME);
    }

    public Map<Integer, String> fields() {
        return fields;
    }

    public byte[] raw() {
        return raw.clone();
    }

    public char delimiter() {
        return delimiter;
    }

    public List<Integer> order() {
        return order;
    }

    public String bodyLine() {
        return new String(raw, java.nio.charset.StandardCharsets.US_ASCII)
                .replace(FixChecks.SOH, '|');
    }

    /** Body fields (excluding 8,9,10) in original order, rendered tag=value SOH-joined. */
    public String canonicalBody() {
        StringBuilder sb = new StringBuilder();
        for (int tag : order) {
            if (tag == FixTags.BEGIN_STRING || tag == FixTags.BODY_LENGTH
                    || tag == FixTags.CHECK_SUM) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(FixChecks.SOH);
            }
            sb.append(tag).append('=').append(fields.get(tag));
        }
        return sb.toString();
    }
}
