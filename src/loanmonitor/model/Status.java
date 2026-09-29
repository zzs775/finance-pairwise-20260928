package loanmonitor.model;

/**
 * Lifecycle status of one (covenant, test period) pair.
 * compliant -> breached -> cure_pending -> defaulted; breached may become
 * waived (evidence retained) or compliant when cured within grace.
 */
public enum Status {
    COMPLIANT,
    BREACHED,
    CURE_PENDING,
    WAIVED,
    DEFAULTED
}
