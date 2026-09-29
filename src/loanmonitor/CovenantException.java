package loanmonitor;

/**
 * Classified input/specification errors. Error code is part of canonical
 * output so fixtures can assert exact categories.
 */
@SuppressWarnings("serial")
public class CovenantException extends RuntimeException {

    public enum Code {
        DUPLICATE_FACT,
        UNIT_CONFLICT,
        PERIOD_OVERLAP,
        DIVISION_BY_ZERO,
        MISSING_DEFINITION,
        INVALID_PERIOD,
        OVERFLOW
    }

    private final Code code;

    public CovenantException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
