package loanengine.dates;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.util.Set;

public final class BusinessDayCalendar {

    private final Set<LocalDate> holidays;

    public BusinessDayCalendar(Set<LocalDate> holidays) {
        this.holidays = holidays == null ? Set.of() : Set.copyOf(holidays);
    }

    public boolean isBusinessDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
        return !weekend && !holidays.contains(date);
    }

    public Set<LocalDate> holidays() {
        return holidays;
    }
}
