package fi;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.time.DayOfWeek;

public record HolidayCalendar(String name, Set<LocalDate> holidays) {

    public static HolidayCalendar of(String name, LocalDate... dates) {
        Set<LocalDate> set = new LinkedHashSet<>();
        for (LocalDate date : dates) {
            set.add(date);
        }
        return new HolidayCalendar(name, Collections.unmodifiableSet(set));
    }

    public boolean isHoliday(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY
                || holidays.contains(date);
    }

    public boolean isBusinessDay(LocalDate date) {
        return !isHoliday(date);
    }
}
