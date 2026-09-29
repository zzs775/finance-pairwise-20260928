package loanmonitor.model;

import java.time.LocalDate;
import java.util.Set;

/**
 * A waiver covers exactly one covenant and an explicit set of test period
 * labels within [effectiveDate, expiryDate]. It never deletes breach evidence.
 */
public record Waiver(
        String id,
        String covenantId,
        Set<String> periodLabels,
        LocalDate effectiveDate,
        LocalDate expiryDate) {
}
