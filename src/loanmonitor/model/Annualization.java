package loanmonitor.model;

/**
 * Annualization method. NONE means the value is used as-is; ACTUAL_365
 * multiplies by 365/elapsedDays; TWELVE_MONTHS multiplies by 12/elapsedMonths.
 */
public enum Annualization {
    NONE,
    ACTUAL_365,
    TWELVE_MONTHS
}
