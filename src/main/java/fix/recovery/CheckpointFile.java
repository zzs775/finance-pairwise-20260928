package fix.recovery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Atomic write/read of session checkpoints via temp file + fsync + rename. */
public final class CheckpointFile {
    private final Path file;

    public CheckpointFile(Path file) {
        this.file = file;
    }

    public void write(SessionSnapshot snapshot) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(tmp, encode(snapshot), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE, StandardOpenOption.SYNC);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            try (var ds = Files.newDirectoryStream(file.toAbsolutePath().getParent())) {
                ds.forEach(p -> {
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public SessionSnapshot read() {
        if (!exists()) {
            return null;
        }
        try {
            return decode(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean exists() {
        return Files.isRegularFile(file);
    }

    static String encode(SessionSnapshot s) {
        return "incomingBytes=" + s.incomingBytes()
                + " incomingLines=" + s.incomingLines()
                + " nextInSeqNum=" + s.nextInSeqNum()
                + " nextOutSeqNum=" + s.nextOutSeqNum()
                + " journalSize=" + s.journalSize()
                + " journalEntries=" + s.journalEntries()
                + " digest=" + s.digest()
                + System.lineSeparator();
    }

    static SessionSnapshot decode(String line) {
        long incomingBytes = 0;
        long incomingLines = 0;
        int nextIn = 0;
        int nextOut = 0;
        long journalSize = 0;
        long journalEntries = 0;
        String digest = null;
        for (String pair : line.strip().split("\\s+")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("bad checkpoint token: " + pair);
            }
            String key = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            switch (key) {
                case "incomingBytes" -> incomingBytes = Long.parseLong(value);
                case "incomingLines" -> incomingLines = Long.parseLong(value);
                case "nextInSeqNum" -> nextIn = Integer.parseInt(value);
                case "nextOutSeqNum" -> nextOut = Integer.parseInt(value);
                case "journalSize" -> journalSize = Long.parseLong(value);
                case "journalEntries" -> journalEntries = Long.parseLong(value);
                case "digest" -> digest = value;
                default -> throw new IllegalArgumentException("unknown checkpoint key: " + key);
            }
        }
        if (digest == null) {
            throw new IllegalArgumentException("checkpoint missing digest");
        }
        return new SessionSnapshot(incomingBytes, incomingLines, nextIn, nextOut,
                journalSize, journalEntries, digest);
    }
}
