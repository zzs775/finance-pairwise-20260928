package loanmonitor.model;

import java.time.LocalDate;

/**
 * A single deterministic alert. Alerts are ordered by (date, covenantId,
 * periodLabel, type) so input ordering never changes canonical output.
 */
public record Alert(
        LocalDate date,
        String covenantId,
        String periodLabel,
        AlertType type,
        String detail) {
}
