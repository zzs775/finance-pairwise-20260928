package covenant;

import java.time.LocalDate;

public record Waiver(String id,
                     String borrower,
                     String testId,
                     LocalDate periodStart,
                     LocalDate periodEnd,
                     LocalDate grantedDate,
                     LocalDate expiresDate) {
}
