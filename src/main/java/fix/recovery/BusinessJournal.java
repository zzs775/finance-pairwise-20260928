package fix.recovery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only application journal. Every committed batch ends with a
 * {@code COMMIT n=.. line=.. bytes=.. next=..} decision record; on open, bytes
 * beyond the final COMMIT record (an unresolved two-phase commit) are
 * truncated. The journal is the authoritative recovery log.
 */
public final class BusinessJournal {
    static final String COMMIT_PREFIX = "COMMIT ";

    private final Path file;
    private final byte[] newline = System.lineSeparator().getBytes(StandardCharsets.UTF_8);
    private final List<String> kept = new ArrayList<>();
    private long applicationBytes;
    private long committedEntries;
    private long endLine;
    private long consumedBytes;
    private int nextInSeqNum = 1;
    private List<String> pending = new ArrayList<>();

    public BusinessJournal(Path file) {
        this.file = file;
    }

    public void open() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            List<String> all = Files.exists(file)
                    ? Files.readAllLines(file, StandardCharsets.UTF_8) : new ArrayList<>();
            int cut = 0;
            long bytes = 0;
            long entries = 0;
            long lastLine = 0;
            long lastInBytes = 0;
            int lastNext = 1;
            for (int i = 0; i < all.size(); i++) {
                String line = all.get(i);
                if (line.startsWith(COMMIT_PREFIX)) {
                    CommitRecord rec = CommitRecord.parse(line.substring(COMMIT_PREFIX.length()));
                    if (rec.batchSize() < 0 || i - cut < rec.batchSize()) {
                        throw new RecoveryAbortedException("corrupt COMMIT record: " + line);
                    }
                    for (int j = cut; j < i; j++) {
                        bytes += lineBytes(all.get(j));
                        entries++;
                    }
                    cut = i + 1;
                    lastLine = rec.endLine();
                    lastInBytes = rec.bytes();
                    lastNext = rec.nextInSeqNum();
                }
            }
            kept.clear();
            kept.addAll(all.subList(0, cut));
            if (cut < all.size()) {
                StringBuilder sb = new StringBuilder();
                for (String line : kept) {
                    sb.append(line).append(System.lineSeparator());
                }
                Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            }
            this.applicationBytes = bytes;
            this.committedEntries = entries;
            this.endLine = lastLine;
            this.consumedBytes = lastInBytes;
            this.nextInSeqNum = lastNext;
            this.pending = new ArrayList<>();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Applies batch application lines (phase-1 data) without declaring commit. */
    public void appendBatch(List<String> lines) {
        try {
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                validate(line);
                sb.append(line).append(System.lineSeparator());
                pending.add(line);
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Phase 1 barrier: batch data is durable on disk. */
    public void prepare() {
    }

    /** Phase 2: durable decision record making the batch visible. */
    public void commit(CommitRecord record) {
        try {
            if (record.batchSize() != pending.size()) {
                throw new RecoveryAbortedException(
                        "COMMIT size " + record.batchSize() + " != pending " + pending.size());
            }
            Files.writeString(file, COMMIT_PREFIX + record.encode() + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            for (String line : pending) {
                applicationBytes += lineBytes(line);
            }
            kept.addAll(pending);
            kept.add(COMMIT_PREFIX + record.encode());
            committedEntries += record.batchSize();
            endLine = record.endLine();
            consumedBytes = record.bytes();
            nextInSeqNum = record.nextInSeqNum();
            pending = new ArrayList<>();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<String> committedEntries() {
        List<String> out = new ArrayList<>();
        for (String line : kept) {
            if (!line.startsWith(COMMIT_PREFIX)) {
                out.add(line);
            }
        }
        return List.copyOf(out);
    }

    /** Size of committed application data only (excludes COMMIT records). */
    public long committedSize() {
        return applicationBytes;
    }

    /** Size once the supplied batch lines are committed (excludes COMMIT records). */
    public long sizeAfterCommit(List<String> batchLines) {
        long size = applicationBytes;
        for (String line : batchLines) {
            size += lineBytes(line);
        }
        return size;
    }

    public long committedCount() {
        return committedEntries;
    }

    public long endLine() {
        return endLine;
    }

    public long consumedBytes() {
        return consumedBytes;
    }

    public int nextInSeqNum() {
        return nextInSeqNum;
    }

    public void close() {
    }

    private int lineBytes(String line) {
        return line.getBytes(StandardCharsets.UTF_8).length + newline.length;
    }

    private void validate(String line) {
        if (line == null || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0
                || line.startsWith(COMMIT_PREFIX)) {
            throw new IllegalArgumentException("reserved journal line: " + line);
        }
    }
}
