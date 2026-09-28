package fix.recovery;

/** BodyLength and CheckSum rules for the FIX wire subset. */
public final class FixChecks {
    public static final char SOH = '\u0001';

    private FixChecks() {
    }

    public static int checksum(byte[] bytes, int from, int to) {
        int sum = 0;
        for (int i = from; i < to; i++) {
            sum += bytes[i] & 0xFF;
        }
        return sum & 0xFF;
    }

    /**
     * Returns the actual body length of a normalized (SOH delimited) message:
     * bytes strictly between tag 9's terminating SOH and the start of tag 10.
     */
    public static int actualBodyLength(byte[] raw) {
        int afterLen = indexAfterField(raw, 0, FixTags.BODY_LENGTH);
        if (afterLen < 0) {
            return -1;
        }
        int chkStart = fieldStart(raw, FixTags.CHECK_SUM);
        if (chkStart < 0) {
            return -1;
        }
        return chkStart - afterLen;
    }

    public static String formatChecksum(int value) {
        return String.format("%03d", value & 0xFF);
    }

    /** Position immediately after the SOH terminating the given tag, or -1. */
    public static int indexAfterField(byte[] raw, int from, int tag) {
        int start = fieldStart(raw, from, tag);
        if (start < 0) {
            return -1;
        }
        int soh = indexOfByte(raw, (byte) SOH, start + tagPrefix(tag).length());
        return soh < 0 ? -1 : soh + 1;
    }

    /** Start index of a field occurring at message level (after a SOH or at 0). */
    public static int fieldStart(byte[] raw, int tag) {
        return fieldStart(raw, 0, tag);
    }

    public static int fieldStart(byte[] raw, int from, int tag) {
        byte[] prefix = tagPrefix(tag).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int idx = indexOfBytes(raw, prefix, from);
        while (idx >= 0) {
            if (idx == 0 || raw[idx - 1] == (byte) SOH) {
                return idx;
            }
            idx = indexOfBytes(raw, prefix, idx + 1);
        }
        return -1;
    }

    public static String tagPrefix(int tag) {
        return tag + "=";
    }

    public static int indexOfByte(byte[] data, byte target, int from) {
        for (int i = Math.max(from, 0); i < data.length; i++) {
            if (data[i] == target) {
                return i;
            }
        }
        return -1;
    }

    public static int indexOfBytes(byte[] data, byte[] target, int from) {
        outer:
        for (int i = Math.max(from, 0); i <= data.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (data[i + j] != target[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
