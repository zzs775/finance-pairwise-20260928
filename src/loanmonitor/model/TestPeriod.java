package loanmonitor.model;

import java.time.LocalDate;

/**
 * A covenant test period with fixed boundary semantics: [periodStart,
 * periodEnd] inclusive for fact selection; periodEnd is the certification /
 * test date (point-in-time anchor).
 */
public record TestPeriod(
        String label,
        LocalDate periodStart,
        LocalDate periodEnd) {
}
