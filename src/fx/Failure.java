package fx;

/** Classification of all domain-level failures. */
public enum Failure {
    INVALID_CURRENCY,
    INVALID_PAIR,
    CURVE_HOLE,
    NEGATIVE_DISCOUNT_FACTOR,
    DATE_ORDER,
    ARITHMETIC_OVERFLOW,
    SNAPSHOT_INVALID,
    PUBLISH_FAILED,
    ILLEGAL_TENOR,
    TOLERANCE_EXCEEDED,
    FIXTURE_ERROR
}
