package fix.recovery;

/** Machine readable categories of rejected input lines. */
public enum RejectCode {
    EMPTY_LINE(Severity.INFO),
    TRUNCATED_LINE(Severity.ERROR),
    MALFORMED_FIELD(Severity.ERROR),
    DUPLICATE_TAG(Severity.ERROR),
    INVALID_BODY_LENGTH(Severity.ERROR),
    INVALID_CHECKSUM(Severity.ERROR),
    INVALID_TIME(Severity.ERROR),
    MISSING_REQUIRED_TAG(Severity.ERROR),
    INVALID_MSG_TYPE(Severity.ERROR),
    SEQ_NOT_NUMBER(Severity.ERROR),
    SEQ_OUT_OF_RANGE(Severity.FATAL);

    private final Severity severity;

    RejectCode(Severity severity) {
        this.severity = severity;
    }

    public Severity severity() {
        return severity;
    }
}
