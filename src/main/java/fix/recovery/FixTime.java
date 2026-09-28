package fix.recovery;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/** Strict parsing/formatting of FIX SendingTime values (UTC). */
public final class FixTime {
    public static final String PATTERN = "yyyyMMdd-HH:mm:ss.SSS";

    private static final DateTimeFormatter STRICT = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.YEAR, 4)
            .appendValue(ChronoField.MONTH_OF_YEAR, 2)
            .appendValue(ChronoField.DAY_OF_MONTH, 2)
            .appendLiteral('-')
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .appendLiteral('.')
            .appendValue(ChronoField.MILLI_OF_SECOND, 3)
            .toFormatter()
            .withZone(ZoneOffset.UTC);

    private FixTime() {
    }

    /** Parses a FIX timestamp to epoch millis; rejects lenient/illegal values. */
    public static long parseEpochMillis(String value) {
        if (value == null || !value.matches("\\d{8}-\\d{2}:\\d{2}:\\d{2}\\.\\d{3}")) {
            throw new IllegalArgumentException("invalid time: " + value);
        }
        try {
            LocalDateTime parsed = LocalDateTime.parse(value, STRICT);
            return parsed.toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid time: " + value, e);
        }
    }

    public static String formatEpochMillis(long epochMillis) {
        return STRICT.format(java.time.Instant.ofEpochMilli(epochMillis));
    }
}
