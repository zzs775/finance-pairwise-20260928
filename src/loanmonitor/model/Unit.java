package loanmonitor.model;

/**
 * Measurement units for facts and metric results. Only strictly compatible
 * units may be combined additively; ratios are {@link #RATIO} (dimensionless).
 */
public enum Unit {
    USD,
    DAYS,
    COUNT,
    RATIO;

    /**
     * Two units are compatible for additive combination only when identical.
     * A dimensionless ratio is never compatible with a dimensional unit.
     */
    public boolean compatibleWith(Unit other) {
        return this == other;
    }
}
