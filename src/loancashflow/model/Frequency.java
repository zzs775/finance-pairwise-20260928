package loancashflow.model;

/** Payment / accrual frequency. */
public enum Frequency {
    ANNUAL(12, 1),
    SEMIANNUAL(6, 2),
    QUARTERLY(3, 4),
    MONTHLY(1, 12);

    private final int monthsPerPeriod;
    private final int periodsPerYear;

    Frequency(int monthsPerPeriod, int periodsPerYear) {
        this.monthsPerPeriod = monthsPerPeriod;
        this.periodsPerYear = periodsPerYear;
    }

    public int monthsPerPeriod() {
        return monthsPerPeriod;
    }

    public int periodsPerYear() {
        return periodsPerYear;
    }
}
