package covenant;

import java.time.LocalDate;

public record Alert(LocalDate date,
                    String borrower,
                    String testId,
                    String periodLabel,
                    AlertType type,
                    String message) {
}
