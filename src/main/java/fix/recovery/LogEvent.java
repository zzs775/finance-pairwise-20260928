package fix.recovery;

/** One graded event recorded while consuming the synthetic log. */
public record LogEvent(long lineNo, Severity severity, RejectCode code, String detail) {
}
