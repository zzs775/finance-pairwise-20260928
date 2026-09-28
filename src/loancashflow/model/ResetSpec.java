package loancashflow.model;

import java.time.LocalDate;

/** Reset cadence: resets occur every {@code frequency} months starting at {@code firstResetDate}. */
public record ResetSpec(LocalDate firstResetDate, Frequency frequency) {
}
