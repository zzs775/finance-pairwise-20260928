package fi;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Unadjusted accrual boundary generation.
 *
 * Regular dates are anchored at maturity and rolled backward in frequency
 * increments. Stub handling:
 *
 * NONE          valueDate equals the first regular date.
 * SHORT_FINAL   regular dates from valueDate forward; final stub to maturity.
 * LONG_FINAL    as SHORT_FINAL, but the final stub absorbs the last regular period.
 * SHORT_INITIAL regular dates back to the first date on/after valueDate;
 *               initial stub starts at valueDate.
 * LONG_INITIAL  as SHORT_INITIAL, but the initial stub absorbs the first regular period.
 *
 * EOM: when valueDate is the last day of its month, every rolled date lands on
 * the last day of its month (including February 28/29).
 */
final class UnadjustedSchedule {

    private UnadjustedSchedule() {
    }

    static List<LocalDate> boundaries(BondTerms terms) {
        return switch (terms.stubType()) {
            case NONE -> noStub(terms);
            case SHORT_FINAL -> shortFinal(terms, false);
            case LONG_FINAL -> shortFinal(terms, true);
            case SHORT_INITIAL -> shortInitial(terms, false);
            case LONG_INITIAL -> shortInitial(terms, true);
        };
    }

    static List<LocalDate> backwardDates(BondTerms terms) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate cursor = terms.maturityDate();
        dates.add(cursor);
        while (true) {
            LocalDate previous = minusMonths(cursor, terms.frequency().months(), terms.eom());
            if (!previous.isAfter(terms.valueDate())) {
                break;
            }
            dates.add(previous);
            cursor = previous;
        }
        return dates.reversed();
    }

    private static List<LocalDate> noStub(BondTerms terms) {
        List<LocalDate> dates = backwardDates(terms);
        LocalDate first = dates.get(0);
        if (first.equals(terms.valueDate())) {
            return dates;
        }
        LocalDate oneMore = minusMonths(first, terms.frequency().months(), terms.eom());
        if (!oneMore.equals(terms.valueDate())) {
            throw new ScheduleException(ErrorCode.CONFLICTING_TERMS,
                    "stubType NONE requires whole number of periods");
        }
        List<LocalDate> result = new ArrayList<>();
        result.add(terms.valueDate());
        result.addAll(dates);
        return result;
    }

    private static List<LocalDate> shortFinal(BondTerms terms, boolean longStub) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate cursor = terms.valueDate();
        dates.add(cursor);
        while (true) {
            LocalDate next = plusMonths(cursor, terms.frequency().months(), terms.eom());
            if (!next.isBefore(terms.maturityDate())) {
                break;
            }
            dates.add(next);
            cursor = next;
        }
        if (longStub && dates.size() > 1) {
            dates.remove(dates.size() - 1);
        }
        dates.add(terms.maturityDate());
        return dates;
    }

    private static List<LocalDate> shortInitial(BondTerms terms, boolean longStub) {
        List<LocalDate> regular = backwardDates(terms);
        int from = 0;
        if (longStub && regular.size() > 1) {
            from = 1;
        }
        List<LocalDate> dates = new ArrayList<>();
        dates.add(terms.valueDate());
        for (int i = from; i < regular.size(); i++) {
            dates.add(regular.get(i));
        }
        return dates;
    }

    static LocalDate plusMonths(LocalDate date, int months, boolean eom) {
        boolean atMonthEnd = date.getDayOfMonth() == date.lengthOfMonth();
        LocalDate shifted = date.plusMonths(months);
        if (eom && atMonthEnd) {
            shifted = shifted.withDayOfMonth(shifted.lengthOfMonth());
        }
        return shifted;
    }

    static LocalDate minusMonths(LocalDate date, int months, boolean eom) {
        boolean atMonthEnd = date.getDayOfMonth() == date.lengthOfMonth();
        LocalDate shifted = date.minusMonths(months);
        if (eom && atMonthEnd) {
            shifted = shifted.withDayOfMonth(shifted.lengthOfMonth());
        }
        return shifted;
    }
}
