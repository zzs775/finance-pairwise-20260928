package loanmonitor.model;

import java.math.BigDecimal;

/**
 * One covenant test. The threshold comparison depends on {@link TestType}:
 * RATIO/MINIMUM require metric >= threshold; MAXIMUM requires metric <=
 * threshold; PAYMENT_DATE ignores metricFormula and checks payment timeliness
 * with {@code graceDays} calendar days injected by the logical clock.
 */
public record CovenantDefinition(
        String id,
        String borrower,
        TestType type,
        MetricSpec metric,
        BigDecimal threshold,
        int graceDays) {
}
