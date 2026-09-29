package loanmonitor.model;

/** Canonical alert categories emitted by the engine. */
public enum AlertType {
    BREACH_DETECTED,
    CURE_PENDING,
    CURED,
    GRACE_EXPIRED,
    DEFAULTED,
    WAIVER_APPLIED,
    RESTATEMENT
}
