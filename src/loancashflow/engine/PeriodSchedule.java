package loancashflow.engine;

import loancashflow.model.Contract;
import loancashflow.model.Frequency;
import loancashflow.model.HolidayCalendar;
import loancashflow.model.BusinessDayConvention;
import loancashflow.model.StubConvention;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.ArrayList;

/**
 * Accrual period date construction. Payment dates may be business-day adjusted; the
 * adjusted date is never written back into the accrual boundaries.
 */
public final class PeriodSchedule {

    private PeriodSchedule() {
    }

    /** Unadjusted ideal period end (and payment) dates from value date to maturity. */
    public static List<LocalDate> idealDates(LocalDate start, Frequency frequency,
                                             int periodCount, boolean endOfMonthRule) {
        List<LocalDate> dates = new ArrayList<>(periodCount + 1);
        LocalDate anchor = start;
        boolean anchorLastDay = start.getDayOfMonth() == start.lengthOfMonth();
        dates.add(anchor);
        for (int index = 1; index <= periodCount; index++) {
            LocalDate next;
            if (endOfMonthRule && anchorLastDay) {
                next = anchor.plusMonths((long) frequency.monthsPerPeriod() * index)
                        .with(TemporalAdjusters.lastDayOfMonth());
            } else {
                next = anchor.plusMonths((long) frequency.monthsPerPeriod() * index);
            }
            dates.add(next);
        }
        return dates;
    }

    public static List<Period> generate(Contract contract) {
        LocalDate valueDate = contract.valueDate();
        LocalDate firstPaymentDate = contract.firstPaymentDate();
        LocalDate maturityDate = contract.maturityDate();

        List<LocalDate> boundaries = new ArrayList<>();
        boundaries.add(valueDate);
        boundaries.add(firstPaymentDate);

        if (contract.stubConvention() == StubConvention.LONG_FIRST) {
            expandLongFirst(boundaries, contract);
        }
        if (firstPaymentDate.isBefore(maturityDate)) {
            appendRegularDates(boundaries, contract);
        } else if (!firstPaymentDate.equals(maturityDate)) {
            throw new LoanException(ErrorCode.INVALID_DATE,
                    "firstPaymentDate " + firstPaymentDate + " after maturity " + maturityDate);
        }

        List<Period> periods = new ArrayList<>(boundaries.size() - 1);
        for (int index = 1; index < boundaries.size(); index++) {
            LocalDate start = boundaries.get(index - 1);
            LocalDate end = boundaries.get(index);
            LocalDate paymentDate = adjustPayment(end, contract.businessDayConvention(),
                    contract.calendar());
            periods.add(new Period(index, start, end, end, paymentDate));
        }
        return periods;
    }

    static LocalDate adjustPayment(LocalDate date, BusinessDayConvention convention,
                                   HolidayCalendar calendar) {
        return convention.adjust(date, calendar);
    }

    private static void expandLongFirst(List<LocalDate> boundaries, Contract contract) {
        LocalDate valueDate = contract.valueDate();
        LocalDate firstPaymentDate = contract.firstPaymentDate();
        LocalDate maturityDate = contract.maturityDate();
        // Regular boundaries are anchored on maturity. Insert any regular boundary that
        // lies strictly after value date and before the (long) first payment date.
        List<LocalDate> inside = new ArrayList<>();
        int stepsBack = 0;
        while (true) {
            stepsBack++;
            LocalDate candidate = shift(maturityDate, contract, -stepsBack);
            if (!candidate.isAfter(valueDate)) {
                break;
            }
            if (candidate.isBefore(firstPaymentDate)) {
                inside.add(candidate);
            }
        }
        for (int index = inside.size() - 1; index >= 0; index--) {
            boundaries.add(1, inside.get(index));
        }
    }

    private static void appendRegularDates(List<LocalDate> boundaries, Contract contract) {
        LocalDate maturityDate = contract.maturityDate();
        LocalDate firstPaymentDate = contract.firstPaymentDate();
        int steps = 1;
        while (true) {
            LocalDate next = shift(firstPaymentDate, contract, steps);
            int comparison = next.compareTo(maturityDate);
            if (comparison == 0) {
                boundaries.add(next);
                return;
            }
            if (comparison > 0) {
                if (contract.stubConvention() == StubConvention.NONE) {
                    throw new LoanException(ErrorCode.INVALID_TERM,
                            "regular schedule from " + firstPaymentDate + " never lands on "
                                    + "maturity " + maturityDate + " (stub NONE)");
                }
                LocalDate previous = boundaries.get(boundaries.size() - 1);
                if (!previous.equals(maturityDate)) {
                    boundaries.add(maturityDate);
                }
                return;
            }
            boundaries.add(next);
            steps++;
        }
    }

    private static LocalDate shift(LocalDate anchor, Contract contract, long signedSteps) {
        long months = (long) contract.frequency().monthsPerPeriod() * signedSteps;
        LocalDate shifted = anchor.plusMonths(months);
        if (contract.endOfMonthRule()
                && anchor.getDayOfMonth() == anchor.lengthOfMonth()) {
            shifted = shifted.with(TemporalAdjusters.lastDayOfMonth());
        }
        return shifted;
    }
}
