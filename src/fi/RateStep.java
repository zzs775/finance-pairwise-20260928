package fi;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RateStep(LocalDate startDate, BigDecimal annualRate) {
}
