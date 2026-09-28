package fi;

public class ScheduleException extends RuntimeException {

    private final ErrorCode code;

    public ScheduleException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
