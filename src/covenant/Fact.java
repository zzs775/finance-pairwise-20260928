package covenant;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Fact(String borrower,
                   String metricId,
                   Unit unit,
                   LocalDate periodStart,
                   LocalDate periodEnd,
                   BigDecimal value,
                   String version,
                   LocalDate asOf,
                   String source) {
}
