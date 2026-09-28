package fi;

public enum Frequency {
    MONTHLY(1),
    QUARTERLY(3),
    SEMI_ANNUAL(6),
    ANNUALLY(12);

    private final int months;

    Frequency(int months) {
        this.months = months;
    }

    public int months() {
        return months;
    }

    public int periodsPerYear() {
        return 12 / months;
    }

    public static Frequency fromMonths(int months) {
        for (Frequency candidate : values()) {
            if (candidate.months == months) {
                return candidate;
            }
        }
        throw new ScheduleException(ErrorCode.ILLEGAL_FREQUENCY,
                "illegal frequency months: " + months);
    }
}
