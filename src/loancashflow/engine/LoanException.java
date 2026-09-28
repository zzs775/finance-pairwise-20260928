package loancashflow.engine;

/** Classified engine error. */
public class LoanException extends RuntimeException {

    private final ErrorCode code;

    public LoanException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public LoanException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
