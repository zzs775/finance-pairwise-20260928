package fix.recovery.test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import fix.recovery.FixChecks;
import fix.recovery.FixTags;
import fix.recovery.MemoryOutboundStore;
import fix.recovery.MsgTypes;
import fix.recovery.RecoveryAbortedException;
import fix.recovery.ResendEntry;
import fix.recovery.ResendPlanner;
import fix.recovery.StoredMessage;

/** Send-side retransmit, GapFill admin replacement, PossDup and OrigSendingTime. */
public final class ResendTest {
    public static void register(TestFramework t) {
        t.run("business messages resend with PossDup and OrigSendingTime", () -> {
            MemoryOutboundStore store = seededStore();
            List<ResendEntry> entries = new ResendPlanner(store).plan(1, 0);
            long business = entries.stream().filter(e -> !e.gapFill()).count();
            TestFramework.eq(3L, business, "three orders resent");
            ResendEntry first = entries.get(0);
            String wire = new String(first.payload(), StandardCharsets.US_ASCII);
            TestFramework.check(wire.contains(FixTags.POSS_DUP_FLAG + "=Y"), "PossDup set");
            TestFramework.check(wire.contains(FixTags.ORIG_SENDING_TIME + "=" + Fixtures.T0),
                    "OrigSendingTime preserved");
            TestFramework.eq(Fixtures.T0, first.origSendingTime(), "original time retained");
        });

        t.run("admin messages collapse into GapFill with no sequence hole", () -> {
            MemoryOutboundStore store = seededStore();
            List<ResendEntry> entries = new ResendPlanner(store).plan(1, 4);
            ResendEntry gap = entries.stream().filter(ResendEntry::gapFill).findFirst().orElseThrow();
            TestFramework.eq(2, gap.seqNum(), "gap starts at heartbeat seq 2");
            String wire = new String(gap.payload(), StandardCharsets.US_ASCII);
            TestFramework.check(wire.contains(FixTags.GAP_FILL_FLAG + "=Y"), "123=Y");
            TestFramework.check(wire.contains(FixTags.NEW_SEQ_NO + "=3"), "jumps over seq 2 only");
            int covered = entries.stream().mapToInt(e -> e.gapFill() ? 0 : 1).sum();
            TestFramework.check(covered <= 3, "business only resent once each");
        });

        t.run("resend end past persisted clamps to last message without holes", () -> {
            MemoryOutboundStore store = seededStore();
            List<ResendEntry> entries = new ResendPlanner(store).plan(1, 99);
            int maxSeq = entries.stream().mapToInt(ResendEntry::seqNum).max().orElse(0);
            TestFramework.check(maxSeq <= 4, "no seq beyond persisted 4: " + maxSeq);
            TestFramework.check(entries.stream().noneMatch(e -> e.seqNum() > 4),
                    "no out-of-bounds records");
        });

        t.run("resend begin past last persisted is bounded error", () -> {
            MemoryOutboundStore store = seededStore();
            int next = store.nextSeqNum();
            TestFramework.fails(() -> new ResendPlanner(store).plan(next + 1, 0),
                    RecoveryAbortedException.class, "begin beyond next seq aborts");
        });

        t.run("request starting at next-unpersisted seq yields empty plan without holes", () -> {
            MemoryOutboundStore store = seededStore();
            int next = store.nextSeqNum();
            List<ResendEntry> entries = new ResendPlanner(store).plan(next, 0);
            TestFramework.eq(0, entries.size(), "nothing persisted to resend, no hole");
        });

        t.run("resend request message is well formed", () -> {
            MemoryOutboundStore store = seededStore();
            byte[] raw = new ResendPlanner(store)
                    .buildResendRequest(7, 0, store.nextSeqNum(), Fixtures.T0);
            String wire = new String(raw, StandardCharsets.US_ASCII).replace(FixChecks.SOH, '|');
            TestFramework.check(wire.contains("35=2"), "ResendRequest type");
            TestFramework.check(wire.contains("7=7"), "BeginSeqNo");
            TestFramework.check(wire.contains("16=0"), "EndSeqNo zero means infinite");
        });

        t.run("illegal resend request range rejected", () -> {
            MemoryOutboundStore store = seededStore();
            TestFramework.fails(() -> new ResendPlanner(store).buildResendRequest(9, 3, 1, Fixtures.T0),
                    RecoveryAbortedException.class, "end before begin");
        });
    }

    /** Persists a deterministic fictitious outbound stream: order,heartbeat,order,order. */
    static MemoryOutboundStore seededStore() {
        MemoryOutboundStore store = new MemoryOutboundStore(1);
        store.append(stored(MsgTypes.NEW_ORDER_SINGLE, 1, Fixtures.T0, "11=ORD1"));
        store.append(stored(MsgTypes.HEARTBEAT, 2, Fixtures.T0, ""));
        store.append(stored(MsgTypes.NEW_ORDER_SINGLE, 3, Fixtures.T0, "11=ORD3"));
        store.append(stored(MsgTypes.NEW_ORDER_SINGLE, 4, Fixtures.T0, "11=ORD4"));
        return store;
    }

    private static StoredMessage stored(String type, int seq, String time, String extra) {
        String body = FixTags.MSG_TYPE + "=" + type + FixChecks.SOH
                + FixTags.SENDER_COMP_ID + "=" + Fixtures.SENDER + FixChecks.SOH
                + FixTags.TARGET_COMP_ID + "=" + Fixtures.TARGET + FixChecks.SOH
                + FixTags.SENDING_TIME + "=" + time + FixChecks.SOH
                + FixTags.MSG_SEQ_NUM + "=" + seq + FixChecks.SOH
                + (extra.isEmpty() ? "" : extra + FixChecks.SOH);
        return new StoredMessage(seq, type, time, body.getBytes(StandardCharsets.US_ASCII));
    }
}
