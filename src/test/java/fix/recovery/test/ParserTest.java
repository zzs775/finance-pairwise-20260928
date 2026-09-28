package fix.recovery.test;

import fix.recovery.FixChecks;
import fix.recovery.FixParser;
import fix.recovery.FixTags;
import fix.recovery.ParseOutcome;
import fix.recovery.RejectCode;
import fix.recovery.Severity;

/** Parser validation: truncation, duplicate tags, time, BodyLength, CheckSum. */
public final class ParserTest {
    public static void register(TestFramework t) {
        FixParser parser = new FixParser();

        t.run("parser accepts well formed message", () -> {
            ParseOutcome o = parser.parse(Fixtures.order(7));
            TestFramework.check(o.ok(), "valid order parsed: " + o.detail());
            TestFramework.eq(7, o.message().seqNum(), "seq 7");
        });

        t.run("parser accepts SOH delimiters", () -> {
            String soh = Fixtures.order(2).replace('|', FixChecks.SOH);
            ParseOutcome o = parser.parse(soh);
            TestFramework.check(o.ok(), "SOH line valid: " + o.detail());
        });

        t.run("standard trailing SOH after checksum accepted", () -> {
            String withTrailer = Fixtures.order(3).replace('|', FixChecks.SOH) + FixChecks.SOH;
            ParseOutcome o = parser.parse(withTrailer);
            TestFramework.check(o.ok(), "trailing SOH valid: " + o.detail());
        });

        t.run("empty line graded INFO", () -> {
            ParseOutcome o = parser.parse("");
            TestFramework.check(!o.ok(), "rejected");
            TestFramework.eq(Severity.INFO, o.code().severity(), "empty is info");
        });

        t.run("trailing delimiter is tolerated but fields missing", () -> {
            ParseOutcome o = parser.parse("8=FIX.4.4|9=100|35=D|");
            TestFramework.eq(RejectCode.MISSING_REQUIRED_TAG, o.code(), "no seq/checksum");
        });

        t.run("missing checksum field rejected", () -> {
            ParseOutcome o = parser.parse("8=FIX.4.4|9=10|35=0|34=1");
            TestFramework.eq(RejectCode.MISSING_REQUIRED_TAG, o.code(), "10 absent");
        });

        t.run("malformed field rejected", () -> {
            ParseOutcome o = parser.parse("garbage|10=000");
            TestFramework.eq(RejectCode.TRUNCATED_LINE, o.code(), "no begin string");
        });

        t.run("duplicate tag rejected", () -> {
            String dup = Fixtures.order(1).replace("10=", "34=999|10=");
            ParseOutcome o = parser.parse(dup);
            TestFramework.eq(RejectCode.DUPLICATE_TAG, o.code(), "34 repeated");
        });

        t.run("bad body length rejected", () -> {
            String bad = Fixtures.order(1).replaceFirst("9=\\d+", "9=1");
            ParseOutcome o = parser.parse(bad);
            TestFramework.eq(RejectCode.INVALID_BODY_LENGTH, o.code(), "length mismatch");
        });

        t.run("bad checksum rejected", () -> {
            String bad = Fixtures.order(1).replaceAll("10=\\d{3}$", "10=000");
            ParseOutcome o = parser.parse(bad);
            TestFramework.eq(RejectCode.INVALID_CHECKSUM, o.code(), "checksum mismatch");
        });

        t.run("invalid time rejected", () -> {
            String good = Fixtures.order(1);
            String bad = good.replace("52=" + Fixtures.T0, "52=20261301-99:99:99.000");
            ParseOutcome o = parser.parse(fixChecksum(bad));
            TestFramework.eq(RejectCode.INVALID_TIME, o.code(), "illegal calendar time");
        });

        t.run("unknown message type rejected", () -> {
            String bad = Fixtures.order(1).replace("35=D", "35=Z");
            ParseOutcome o = parser.parse(fixChecksum(bad));
            TestFramework.eq(RejectCode.INVALID_MSG_TYPE, o.code(), "unknown type");
        });

        t.run("out of range sequence is FATAL grade", () -> {
            String bad = Fixtures.order(1).replace("34=1", "34=99999999999");
            ParseOutcome o = parser.parse(fixChecksum(bad));
            TestFramework.eq(RejectCode.SEQ_OUT_OF_RANGE, o.code(), "huge seq");
            TestFramework.eq(Severity.FATAL, o.code().severity(), "fatal grade");
        });

        t.run("zero sequence rejected", () -> {
            String bad = Fixtures.order(1).replace("34=1", "34=0");
            ParseOutcome o = parser.parse(fixChecksum(bad));
            TestFramework.eq(RejectCode.SEQ_OUT_OF_RANGE, o.code(), "seq zero");
        });

        t.run("required tag missing rejected", () -> {
            String bad = Fixtures.order(1).replace(FixTags.SENDING_TIME + "=" + Fixtures.T0 + "|", "");
            ParseOutcome o = parser.parse(fixChecksum(bad));
            TestFramework.eq(RejectCode.MISSING_REQUIRED_TAG, o.code(), "missing 52");
        });
    }

    /** Recomputes 8/9/10 after editing a |-delimited fixture line. */
    static String fixChecksum(String pipeLine) {
        String soh = pipeLine.replace('|', FixChecks.SOH);
        int first = soh.indexOf(FixChecks.SOH) + 1;
        int second = soh.indexOf(FixChecks.SOH, first) + 1;
        int ten = soh.lastIndexOf((char) 1 + "10=");
        int bodyStart = second;
        int bodyEnd = soh.lastIndexOf(FixChecks.SOH);
        String body = soh.substring(bodyStart, bodyEnd + 1);
        String head = FixTags.BEGIN_STRING + "=FIX.4.4" + FixChecks.SOH
                + FixTags.BODY_LENGTH + "="
                + body.getBytes(java.nio.charset.StandardCharsets.US_ASCII).length
                + FixChecks.SOH;
        byte[] headBody = (head + body).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int sum = FixChecks.checksum(headBody, 0, headBody.length);
        String rebuilt = head + body + FixTags.CHECK_SUM + "=" + FixChecks.formatChecksum(sum);
        return rebuilt.replace(FixChecks.SOH, '|');
    }
}
