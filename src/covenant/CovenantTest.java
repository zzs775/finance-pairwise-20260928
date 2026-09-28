package covenant;

import java.math.BigDecimal;

public record CovenantTest(String id,
                           TestKind kind,
                           String metricId,
                           String numeratorMetric,
                           String denominatorMetric,
                           BigDecimal threshold,
                           Unit unit,
                           int graceDays,
                           String paymentId,
                           boolean breachIfMissing) {
}
