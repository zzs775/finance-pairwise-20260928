package fix.recovery;

/** Parser result: either a valid message or a graded rejection. */
public record ParseOutcome(boolean ok, FixMessage message, RejectCode code, String detail) {
    public static ParseOutcome valid(FixMessage message) {
        return new ParseOutcome(true, message, null, null);
    }

    public static ParseOutcome reject(RejectCode code, String detail) {
        return new ParseOutcome(false, null, code, detail);
    }
}
