package loanmonitor.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An immutable, published monitoring result as of one logical date.
 * Replaced only by a fully successful rebuild; a failed restatement never
 * mutates the current snapshot.
 */
public record MonitoringSnapshot(
        LocalDate asOf,
        int publishVersion,
        Map<BreachKey, Status> statuses,
        Map<BreachKey, MetricResult> metrics,
        List<BreachRecord> breaches,
        List<Alert> alerts) {

    public String toCanonicalText() {
        StringBuilder sb = new StringBuilder();
        sb.append("SNAPSHOT asOf=").append(asOf)
                .append(" publishVersion=").append(publishVersion).append('\n');

        Map<BreachKey, Status> sortedStatuses = new TreeMap<>(
                java.util.Comparator.comparing(BreachKey::covenantId)
                        .thenComparing(BreachKey::periodLabel));
        sortedStatuses.putAll(statuses);
        for (Map.Entry<BreachKey, Status> entry : sortedStatuses.entrySet()) {
            sb.append("STATUS ").append(entry.getKey().covenantId())
                    .append(' ').append(entry.getKey().periodLabel())
                    .append(' ').append(entry.getValue()).append('\n');
        }

        Map<BreachKey, MetricResult> sortedMetrics = new TreeMap<>(
                java.util.Comparator.comparing(BreachKey::covenantId)
                        .thenComparing(BreachKey::periodLabel));
        sortedMetrics.putAll(metrics);
        for (MetricResult result : sortedMetrics.values()) {
            sb.append("METRIC ").append(result.covenantId())
                    .append(' ').append(result.periodLabel())
                    .append(' ').append(result.metricName())
                    .append(' ').append(result.value().toPlainString())
                    .append(' ').append(result.unit())
                    .append(" window=[").append(result.windowStart())
                    .append(',').append(result.windowEnd()).append("]\n");
            List<MetricResult.SourceRef> srcs = new ArrayList<>(result.sources());
            srcs.sort(java.util.Comparator
                    .comparing(MetricResult.SourceRef::factId)
                    .thenComparingInt(MetricResult.SourceRef::version));
            for (MetricResult.SourceRef src : srcs) {
                sb.append("  SOURCE ").append(src.factId())
                        .append(" v").append(src.version())
                        .append(' ').append(src.account())
                        .append('=').append(src.value().toPlainString())
                        .append(' ').append(src.unit())
                        .append(" as ").append(src.role()).append('\n');
            }
            for (String line : result.formulaTrace().split("\n", -1)) {
                sb.append("  TRACE ").append(line).append('\n');
            }
        }

        List<BreachRecord> sortedBreaches = new ArrayList<>(breaches);
        sortedBreaches.sort(java.util.Comparator
                .comparing((BreachRecord b) -> b.key().covenantId())
                .thenComparing(b -> b.key().periodLabel()));
        for (BreachRecord breach : sortedBreaches) {
            sb.append("BREACH ").append(breach.key().covenantId())
                    .append(' ').append(breach.key().periodLabel())
                    .append(" date=").append(breach.breachDate())
                    .append(" waived=").append(breach.waived())
                    .append(" superseded=").append(breach.superseded());
            if (breach.waiverId() != null) {
                sb.append(" waiver=").append(breach.waiverId());
            }
            sb.append(" reason=").append(breach.reason()).append('\n');
        }

        List<Alert> sortedAlerts = new ArrayList<>(alerts);
        sortedAlerts.sort(java.util.Comparator
                .comparing(Alert::date)
                .thenComparing(Alert::covenantId)
                .thenComparing(Alert::periodLabel)
                .thenComparing(a -> a.type().name())
                .thenComparing(Alert::detail,
                        java.util.Comparator.nullsFirst(
                                java.util.Comparator.naturalOrder())));
        for (Alert alert : sortedAlerts) {
            sb.append("ALERT ").append(alert.date())
                    .append(' ').append(alert.covenantId())
                    .append(' ').append(alert.periodLabel())
                    .append(' ').append(alert.type())
                    .append(' ').append(alert.detail() == null ? "" : alert.detail())
                    .append('\n');
        }
        return sb.toString();
    }
}
