package fi;

import java.math.BigDecimal;
import java.time.LocalDate;

public record NotionalStep(LocalDate startDate, BigDecimal outstandingNotional) {
}
