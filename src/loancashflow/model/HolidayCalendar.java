package loancashflow.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/** In-problem holiday calendar: weekend days plus an explicit set of holiday dates. */
public record HolidayCalendar(Set<LocalDate> holidays, Set<DayOfWeek> weekend) {

    public static final Set<DayOfWeek> SAT_SUN =
            Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    public HolidayCalendar {
        holidays = holidays == null ? Set.of() : Set.copyOf(holidays);
        weekend = weekend == null ? Set.of() : Set.copyOf(weekend);
    }

    public static HolidayCalendar noHolidays() {
        return new HolidayCalendar(Set.of(), Set.of());
    }

    /** Standard five-day week plus explicit holiday dates. */
    public static HolidayCalendar of(Set<LocalDate> holidays) {
        return new HolidayCalendar(holidays, SAT_SUN);
    }

    public boolean isBusinessDay(LocalDate date) {
        return !weekend.contains(date.getDayOfWeek()) && !holidays.contains(date);
    }
}
