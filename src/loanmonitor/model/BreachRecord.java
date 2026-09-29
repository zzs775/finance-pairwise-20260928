package loanmonitor.model;

import java.time.LocalDate;

/**
 * Immutable evidence that a covenant was breached for a period. Breach
 * evidence is never erased by waivers or cures; waivers only change the
 * effective status and are recorded separately.
 */
public record BreachRecord(
        BreachKey key,
        LocalDate breachDate,
        String reason,
        String metricTrace,
        boolean waived,
        String waiverId,
        boolean superseded) {
}
