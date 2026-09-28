package covenant;

import java.time.LocalDate;

public final class Periods {

    private Periods() {
    }

    /**
     * Rolling 12-period window start: [periodEnd - 12 months, periodEnd).
     * The window is half-open; a fact ending exactly at {@code periodEndExclusive}
     * is inside, a fact starting exactly at the window start is inside.
     */
    public static LocalDate rolling12Start(LocalDate periodEndExclusive) {
        if (periodEndExclusive == null) {
            throw new InvalidInputException("period end must not be null");
        }
        return periodEndExclusive.minusMonths(12);
    }

    /**
     * True when two half-open periods [aStart,aEnd) and [bStart,bEnd) overlap.
     * Touching at an endpoint (aEnd == bStart) is not an overlap.
     */
    public static boolean overlaps(LocalDate aStart, LocalDate aEnd,
                                   LocalDate bStart, LocalDate bEnd) {
        if (aStart == null || aEnd == null || bStart == null || bEnd == null) {
            throw new InvalidInputException("period bounds must not be null");
        }
        if (!aEnd.isAfter(aStart) || !bEnd.isAfter(bStart)) {
            throw new InvalidInputException("period end must be strictly after start");
        }
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }

    public static boolean contains(LocalDate outerStart, LocalDate outerEnd,
                                   LocalDate innerStart, LocalDate innerEnd) {
        return !innerStart.isBefore(outerStart) && !innerEnd.isAfter(outerEnd);
    }

    public static String label(LocalDate periodStart, LocalDate periodEnd) {
        return periodStart.toString() + "/" + periodEnd.toString();
    }
}
