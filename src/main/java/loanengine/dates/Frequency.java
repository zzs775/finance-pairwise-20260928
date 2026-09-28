package loanengine.dates;

import java.time.LocalDate;

public enum Frequency {
    MONTHLY(1),
    QUARTERLY(3),
    SEMI_ANNUAL(6),
    ANNUAL(12);

    private final int months;

    Frequency(int months) {
        this.months = months;
    }

    public int months() {
        return months;
    }

    public LocalDate shift(LocalDate base, int periods) {
        return base.plusMonths((long) months * periods);
    }
}
