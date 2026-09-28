package fx;

/** Typed domain exception carrying a {@link Failure} category. */
public final class FxException extends RuntimeException {
    private final Failure failure;

    public FxException(Failure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public Failure failure() {
        return failure;
    }
}
