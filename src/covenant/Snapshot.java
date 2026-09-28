package covenant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.time.LocalDate;
import java.util.List;

public final class Snapshot {

    private final LocalDate asOf;
    private final List<TestResult> results;
    private final List<Alert> alerts;

    public Snapshot(LocalDate asOf, List<TestResult> results, List<Alert> alerts) {
        this.asOf = asOf;
        this.results = results.stream().sorted(resultOrder).toList();
        this.alerts = alerts.stream().sorted(alertOrder).toList();
    }

    public LocalDate asOf() {
        return asOf;
    }

    public List<TestResult> results() {
        return results;
    }

    public List<Alert> alerts() {
        return alerts;
    }

    public String toCanonicalText() {
        StringBuilder sb = new StringBuilder();
        sb.append("SNAPSHOT asOf=").append(asOf).append('\n');
        for (TestResult result : results) {
            sb.append("RESULT\t")
                    .append(result.borrower()).append('\t')
                    .append(result.testId()).append('\t')
                    .append(result.periodStart()).append('/').append(result.periodEnd()).append('\t')
                    .append(result.status()).append('\t')
                    .append(result.value() == null ? "" : result.value().toPlainString()).append('\t')
                    .append(result.firstBreachDate() == null
                            ? "" : result.firstBreachDate().toString()).append('\t')
                    .append(result.statusDate()).append('\t')
                    .append(result.explanation() == null ? "" : result.explanation())
                    .append('\n');
            for (String evidence : result.breachEvidence()) {
                sb.append("EVIDENCE\t").append(result.borrower()).append('\t')
                        .append(result.testId()).append('\t')
                        .append(result.periodStart()).append('/').append(result.periodEnd())
                        .append('\t').append(evidence).append('\n');
            }
        }
        for (Alert alert : alerts) {
            sb.append("ALERT\t").append(alert.date()).append('\t')
                    .append(alert.borrower()).append('\t')
                    .append(alert.testId()).append('\t')
                    .append(alert.periodLabel()).append('\t')
                    .append(alert.type()).append('\t')
                    .append(alert.message()).append('\n');
        }
        return sb.toString();
    }

    public byte[] canonicalBytes() {
        return toCanonicalText().getBytes(StandardCharsets.UTF_8);
    }

    public String digest() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(canonicalBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static final Comparator<TestResult> resultOrder =
            Comparator.comparing(TestResult::borrower)
                    .thenComparing(TestResult::testId)
                    .thenComparing(TestResult::periodStart)
                    .thenComparing(TestResult::periodEnd);

    static final Comparator<Alert> alertOrder =
            Comparator.comparing(Alert::date)
                    .thenComparing(Alert::borrower)
                    .thenComparing(Alert::testId)
                    .thenComparing(Alert::periodLabel)
                    .thenComparing(Alert::type);
}
