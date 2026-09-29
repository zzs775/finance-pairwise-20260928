package loanmonitor.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A synthetic financial fact. Flow facts carry an inclusive [periodStart,
 * periodEnd] range (periodEnd null means open range is illegal); stock facts
 * carry a single asOf date. {@code version} starts at 1 and is incremented by
 * restatements of the same logical fact key.
 */
public record Fact(
        String id,
        String borrower,
        String account,
        BigDecimal value,
        Unit unit,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate asOf,
        int version) {

    public boolean isFlow() {
        return periodStart != null && periodEnd != null;
    }

    /**
     * Logical identity ignoring version: same borrower, account and date
     * coordinates. A restatement reuses this key with an incremented version.
     */
    public String logicalKey() {
        if (isFlow()) {
            return borrower + "|" + account + "|F|" + periodStart + "|" + periodEnd;
        }
        return borrower + "|" + account + "|S|" + asOf;
    }
}
