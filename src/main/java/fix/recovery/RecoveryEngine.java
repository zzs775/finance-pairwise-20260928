package fix.recovery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Offline FIX session recovery engine.
 *
 * <p>Validates a local synthetic inbound log, maintains expected inbound and
 * outbound sequence numbers, buffers out-of-order messages until gaps close,
 * emits canonical resend requests for gaps, applies each business message at
 * most once through a journal-authoritative two-phase commit, and terminates
 * recovery on illegal rollback, GapFill over acknowledged messages, or
 * out-of-range sequence numbers.
 */
public final class RecoveryEngine {
    private final Path workDir;
    private final EventLog eventLog;
    private final OutboundStore outboundStore;
    private final Consumer<CrashPoint> crashHook;
    private final FixParser parser = new FixParser();

    public RecoveryEngine(Path workDir, EventLog eventLog, OutboundStore outboundStore) {
        this(workDir, eventLog, outboundStore, point -> {
        });
    }

    public RecoveryEngine(Path workDir, EventLog eventLog, OutboundStore outboundStore,
                          Consumer<CrashPoint> crashHook) {
        this.workDir = workDir;
        this.eventLog = eventLog;
        this.outboundStore = outboundStore;
        this.crashHook = crashHook;
    }

    public RecoveryResult run(Path inboundLog) {
        try {
            Files.createDirectories(workDir);
            CheckpointFile checkpointFile = new CheckpointFile(workDir.resolve("checkpoint.ckp"));
            BusinessJournal journal = new BusinessJournal(workDir.resolve("business.log"));
            journal.open();

            BusinessState business = BusinessState.fromJournal(journal.committedEntries());
            int nextIn = journal.committedCount() == 0 && !checkpointFile.exists()
                    ? 1 : journal.nextInSeqNum();
            long consumedBytes = journal.consumedBytes();
            long startLine = journal.endLine();

            verifyCheckpoint(checkpointFile, journal, nextIn, business);

            RecoveryState state = new RecoveryState(consumedBytes, startLine, nextIn, business);
            CommitCoordinator coordinator = new CommitCoordinator(journal, checkpointFile);
            List<String> tail = readTail(inboundLog, consumedBytes);
            TreeMap<Integer, FixMessage> buffer = new TreeMap<>();
            int gapRequestedUntil = -1;

            for (int tailIndex = 0; tailIndex < tail.size(); tailIndex++) {
                String rawLine = tail.get(tailIndex);
                if (rawLine.isEmpty() && tailIndex == tail.size() - 1) {
                    continue;
                }
                state.advanceLine(rawLine);
                ParseOutcome outcome = parser.parse(rawLine);
                if (!outcome.ok()) {
                    if (outcome.code().severity() == Severity.FATAL) {
                        throw new RecoveryAbortedException("line " + state.lineNo
                                + ": " + outcome.code() + " " + outcome.detail());
                    }
                    eventLog.record(state.lineNo, outcome.code(), outcome.detail());
                    continue;
                }
                FixMessage msg = outcome.message();
                int seq = msg.seqNum();
                if (seq < state.nextInSeqNum) {
                    handleOldMessage(msg, seq, state);
                    continue;
                }
                if (seq > state.nextInSeqNum && gapRequestedUntil < seq - 1) {
                    emitResendRequest(state.nextInSeqNum, seq - 1, state.lineNo);
                    gapRequestedUntil = seq - 1;
                }
                buffer.merge(seq, msg, (first, second) -> first);
                drain(buffer, state, coordinator, journal, checkpointFile);
                if (!buffer.isEmpty() && buffer.firstKey() > state.nextInSeqNum) {
                    gapRequestedUntil = buffer.firstKey() - 1;
                }
            }

            if (!buffer.isEmpty()) {
                throw new RecoveryAbortedException(
                        "recovery incomplete: missing seq from " + state.nextInSeqNum);
            }
            checkpointFile.write(state.snapshot(outboundStore.nextSeqNum(),
                    journal.committedSize()));
            journal.close();
            return new RecoveryResult(checkpointFile.read(), journal.committedEntries(),
                    eventLog.events());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void drain(TreeMap<Integer, FixMessage> buffer, RecoveryState state,
                       CommitCoordinator coordinator, BusinessJournal journal,
                       CheckpointFile checkpointFile) {
        while (!buffer.isEmpty() && buffer.firstKey() == state.nextInSeqNum) {
            FixMessage msg = buffer.pollFirstEntry().getValue();
            int seq = state.nextInSeqNum;
            if (MsgTypes.SEQUENCE_RESET.equals(msg.msgType())) {
                int newSeq = applySequenceReset(msg, seq, state.lineNo, state.business);
                buffer.headMap(newSeq, false).clear();
                state.nextInSeqNum = newSeq;
                checkpointFile.write(state.snapshot(outboundStore.nextSeqNum(),
                        journal.committedSize()));
                continue;
            }
            if (MsgTypes.isBusinessType(msg.msgType())) {
                String canonical = state.business.apply(msg);
                if (canonical == null) {
                    eventLog.record(state.lineNo, RejectCode.DUPLICATE_TAG,
                            "duplicate business seq " + seq + " not reapplied");
                } else {
                    state.nextInSeqNum = seq + 1;
                    long prospectiveSize = journal.sizeAfterCommit(List.of(canonical));
                    SessionSnapshot after = state.snapshot(outboundStore.nextSeqNum(), prospectiveSize);
                    CommitRecord record = new CommitRecord(1, state.lineNo,
                            state.incomingBytes, state.nextInSeqNum);
                    coordinator.commitOne(canonical, record, after, crashHook);
                    continue;
                }
            }
            state.nextInSeqNum = seq + 1;
        }
    }

    private int applySequenceReset(FixMessage msg, int seq, long lineNo, BusinessState business) {
        int newSeq = Integer.parseInt(msg.tag(FixTags.NEW_SEQ_NO));
        boolean gapFill = "Y".equals(msg.tag(FixTags.GAP_FILL_FLAG));
        if (newSeq <= seq) {
            throw new RecoveryAbortedException(
                    "illegal reset rollback line " + lineNo + ": NewSeqNo=" + newSeq
                            + " must exceed MsgSeqNum=" + seq);
        }
        for (int covered = seq; covered < newSeq; covered++) {
            if (business.isApplied(covered)) {
                throw new RecoveryAbortedException(
                        (gapFill ? "GapFill" : "SequenceReset") + " [" + seq + "," + newSeq
                                + ") covers acknowledged business seq " + covered);
            }
        }
        return newSeq;
    }

    private void handleOldMessage(FixMessage msg, int seq, RecoveryState state) {
        if (MsgTypes.isBusinessType(msg.msgType())) {
            eventLog.record(state.lineNo, RejectCode.DUPLICATE_TAG,
                    "duplicate business seq " + seq
                            + (msg.possDup() ? " PossDup" : "") + " applied at most once");
            return;
        }
        if (MsgTypes.SEQUENCE_RESET.equals(msg.msgType())) {
            throw new RecoveryAbortedException("illegal reset rollback line " + state.lineNo
                    + ": seq=" + seq + " expected>=" + state.nextInSeqNum);
        }
        eventLog.info(state.lineNo, "replay session seq " + seq
                + (msg.possDup() ? " PossDup" : "") + " ignored");
    }

    private void emitResendRequest(int begin, int end, long lineNo) {
        int senderSeq = outboundStore.nextSeqNum();
        byte[] raw = new ResendPlanner(outboundStore)
                .buildResendRequest(begin, end, senderSeq, FixTime.formatEpochMillis(0));
        String rendered = new String(raw, StandardCharsets.US_ASCII).replace(FixChecks.SOH, '|');
        eventLog.info(lineNo, "RESEND_REQUEST begin=" + begin + " end=" + end + " " + rendered);
    }

    private void verifyCheckpoint(CheckpointFile checkpointFile, BusinessJournal journal,
                                  int nextIn, BusinessState business) {
        if (!checkpointFile.exists()) {
            return;
        }
        SessionSnapshot c = checkpointFile.read();
        if (c.nextInSeqNum() != nextIn) {
            eventLog.info(0, "checkpoint ahead of journal; rebuilding from journal");
        }
        if (!c.digest().equals(business.digestHex()) && journal.committedCount() > 0) {
            throw new RecoveryAbortedException(
                    "checkpoint digest " + c.digest() + " != journal digest " + business.digestHex());
        }
    }

    private List<String> readTail(Path file, long skipBytes) throws IOException {
        try (var in = Files.newInputStream(file)) {
            long skipped = in.skip(skipBytes);
            if (skipped != skipBytes) {
                throw new RecoveryAbortedException(
                        "inbound log shorter than committed offset " + skipBytes);
            }
            String tail = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (tail.isEmpty()) {
                return List.of();
            }
            return List.of(tail.split("\\R", -1));
        }
    }

    private static final class RecoveryState {
        long incomingBytes;
        long lineNo;
        int nextInSeqNum;
        final BusinessState business;

        RecoveryState(long incomingBytes, long lineNo, int nextInSeqNum, BusinessState business) {
            this.incomingBytes = incomingBytes;
            this.lineNo = lineNo;
            this.nextInSeqNum = nextInSeqNum;
            this.business = business;
        }

        void advanceLine(String rawLine) {
            incomingBytes += rawLine.getBytes(StandardCharsets.UTF_8).length
                    + System.lineSeparator().getBytes(StandardCharsets.UTF_8).length;
            lineNo++;
        }

        SessionSnapshot snapshot(int nextOutSeqNum, long journalSize) {
            return new SessionSnapshot(incomingBytes, lineNo, nextInSeqNum, nextOutSeqNum,
                    journalSize, business.count(), business.digestHex());
        }
    }
}
