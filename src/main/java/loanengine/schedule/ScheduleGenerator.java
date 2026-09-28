package loanengine.schedule;

import loanengine.core.LoanException;
import loanengine.model.Contract;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class ScheduleGenerator {

    private ScheduleGenerator() {
    }

    public static List<SchedulePeriod> generate(Contract contract) {
        List<LocalDate> unadjusted = buildUnadjustedDates(contract);
        List<SchedulePeriod> periods = new ArrayList<>();
        LocalDate accrualStart = contract.valueDate();
        for (int i = 0; i < unadjusted.size(); i++) {
            LocalDate end = unadjusted.get(i);
            LocalDate adjustedPayment = contract.dayConvention()
                    .adjust(end, contract.calendar());
            LocalDate resetDate = resetDateFor(contract, accrualStart);
            boolean stub = !end.equals(shift(contract, accrualStart, 1));
            periods.add(new SchedulePeriod(i + 1, accrualStart, end, end,
                    adjustedPayment, stub, resetDate));
            accrualStart = end;
        }
        return List.copyOf(periods);
    }

    private static List<LocalDate> buildUnadjustedDates(Contract contract) {
        LocalDate maturity = contract.maturityDate();
        LocalDate value = contract.valueDate();
        LocalDate firstEnd = contract.firstPaymentDate();
        List<LocalDate> result = new ArrayList<>();

        if (firstEnd != null) {
            result.add(firstEnd);
            int guard = 0;
            int index = 1;
            while (true) {
                index++;
                LocalDate next = advanceMonths(firstEnd,
                        (long) contract.frequency().months() * (index - 1L),
                        contract.monthEndRule());
                if (!next.isBefore(maturity) || guard++ > 10_000) {
                    break;
                }
                result.add(next);
            }
            result.add(maturity);
        } else {
            int stepMonths = contract.frequency().months();
            int totalMonths = monthsBetweenWithEom(value, maturity,
                    contract.monthEndRule());
            if (totalMonths > 0 && totalMonths % stepMonths == 0) {
                for (int k = 1; k <= totalMonths / stepMonths; k++) {
                    result.add(advanceMonths(value, (long) k * stepMonths,
                            contract.monthEndRule()));
                }
            } else {
                LocalDate cursor = value;
                int guard = 0;
                while (true) {
                    LocalDate next = shift(contract, cursor, 1);
                    if (!next.isBefore(maturity) || guard++ > 10_000) {
                        break;
                    }
                    result.add(next);
                    cursor = next;
                }
                result.add(maturity);
            }
        }

        if (!result.get(0).isAfter(contract.valueDate())) {
            throw new LoanException(LoanException.Category.INVALID_DATE,
                    "schedule collapsed: first period end " + result.get(0));
        }
        return result;
    }

    private static LocalDate advanceMonths(LocalDate anchor, long months, boolean eom) {
        LocalDate shifted = anchor.plusMonths(months);
        if (eom && anchor.getDayOfMonth() == anchor.lengthOfMonth()
                && shifted.getDayOfMonth() != shifted.lengthOfMonth()) {
            shifted = shifted.withDayOfMonth(shifted.lengthOfMonth());
        }
        return shifted;
    }

    private static int monthsBetweenWithEom(LocalDate start, LocalDate end, boolean eom) {
        if (eom) {
            LocalDate s = start.withDayOfMonth(1);
            LocalDate e = end.withDayOfMonth(1);
            int months = (e.getYear() - s.getYear()) * 12
                    + (e.getMonthValue() - s.getMonthValue());
            return months;
        }
        java.time.YearMonth sy = java.time.YearMonth.from(start);
        java.time.YearMonth ey = java.time.YearMonth.from(end);
        int months = (ey.getYear() - sy.getYear()) * 12
                + (ey.getMonthValue() - sy.getMonthValue());
        if (months > 0) {
            LocalDate candidate = advanceMonths(start, months, false);
            if (!candidate.equals(end)) {
                return -1;
            }
        }
        return months;
    }

    private static LocalDate shift(Contract contract, LocalDate base, int periods) {
        return advanceMonths(base, (long) contract.frequency().months() * periods,
                contract.monthEndRule());
    }


    private static LocalDate resetDateFor(Contract contract, LocalDate accrualStart) {
        if (!contract.reset().isFloating()) {
            return null;
        }
        List<LocalDate> resets = contract.reset().resetDates();
        LocalDate chosen = null;
        for (LocalDate reset : resets) {
            if (!reset.isAfter(accrualStart)) {
                chosen = reset;
            } else {
                break;
            }
        }
        if (chosen == null) {
            throw new LoanException(LoanException.Category.MISSING_FIXING,
                    "no reset date on or before accrual start " + accrualStart);
        }
        return chosen;
    }
}
