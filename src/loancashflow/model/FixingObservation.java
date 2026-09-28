package loancashflow.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A versioned floating-index observation. Higher {@code version} supersedes lower ones
 * for the same reset date (corrections).
 */
public record FixingObservation(LocalDate resetDate, BigDecimal rawRate, int version) {
}
