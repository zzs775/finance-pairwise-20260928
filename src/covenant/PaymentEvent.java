package covenant;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentEvent(String borrower,
                           String paymentId,
                           LocalDate scheduledDate,
                           LocalDate actualDate,
                           BigDecimal amount,
                           Unit unit) {
}
