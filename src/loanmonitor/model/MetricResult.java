package loanmonitor.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The computed value of a metric for one test period, including the ordered
 * list of fact sources (id + version) and the rendered formula tree with
 * operand values.
 */
public record MetricResult(
        String covenantId,
        String periodLabel,
        String metricName,
        BigDecimal value,
        Unit unit,
        List<SourceRef> sources,
        String formulaTrace,
        LocalDate windowStart,
        LocalDate windowEnd) {

    public record SourceRef(String factId, int version, String account,
                            BigDecimal value, Unit unit, String role) {
    }
}
