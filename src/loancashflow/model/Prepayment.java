package loancashflow.model;

import java.math.BigDecimal;

/**
 * A prepayment instruction attached at a frozen in-period ordinal position
 * ({@code periodOrdinal}, 1-based) rather than a calendar date.
 */
public record Prepayment(
        int periodOrdinal,
        BigDecimal amount,
        RecastMode mode) {
}
