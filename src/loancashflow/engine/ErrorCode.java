package loancashflow.engine;

/** Classified validation / computation error categories. */
public enum ErrorCode {
    INVALID_DATE,
    MISSING_FIXING,
    NEGATIVE_PRINCIPAL,
    SCALE_OVERFLOW,
    INVALID_TERM,
    INVALID_RATE,
    INVALID_PREPAYMENT,
    NEGATIVE_BALANCE,
    RESIDUAL_NOT_ABSORBED,
    INTERNAL_ERROR
}
