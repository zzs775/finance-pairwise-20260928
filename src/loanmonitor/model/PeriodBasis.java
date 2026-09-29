package loanmonitor.model;

/**
 * Declared period basis for a metric. These must never be mixed:
 * ROLLING_12 uses the trailing 12-month window ending at the test date,
 * POINT_IN_TIME uses the periodEnd date as an instant anchor,
 * CUMULATIVE sums from a fixed covenant inception date to periodEnd.
 */
public enum PeriodBasis {
    ROLLING_12,
    POINT_IN_TIME,
    CUMULATIVE
}
