package covenant;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record TestResult(String borrower,
                         String testId,
                         LocalDate periodStart,
                         LocalDate periodEnd,
                         Status status,
                         BigDecimal value,
                         String explanation,
                         List<String> breachEvidence,
                         LocalDate firstBreachDate,
                         LocalDate statusDate) {
}
