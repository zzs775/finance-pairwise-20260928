package fx;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Immutable holiday calendar for one currency. */
public record Holidays(Ccy ccy, Set<LocalDate> dates) {

    public Holidays {
        Objects.requireNonNull(ccy, "ccy");
        Objects.requireNonNull(dates, "dates");
        dates = Collections.unmodifiableSet(new TreeSet<>(dates));
    }

    public static Holidays of(Ccy ccy, Set<LocalDate> dates) {
        return new Holidays(ccy, dates);
    }

    public static Holidays empty(Ccy ccy) {
        return new Holidays(ccy, Set.of());
    }

    public boolean isBusinessDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        boolean weekday = dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
        return weekday && !dates.contains(date);
    }
}
