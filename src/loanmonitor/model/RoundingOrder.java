package loanmonitor.model;

/**
 * Declares whether scale/rounding is applied to each intermediate operand
 * before the test comparison (INTERMEDIATE) or only to the final computed
 * metric value (FINAL).
 */
public enum RoundingOrder {
    INTERMEDIATE,
    FINAL
}
