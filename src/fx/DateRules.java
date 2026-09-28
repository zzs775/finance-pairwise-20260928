package fx;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;

/** Spot-date and delivery-date rules driven by two currency holiday calendars. */
public final class DateRules {
    public static final ZoneId FIXED_ZONE = ZoneId.of("UTC");
    private static final int SPOT_LAG_DAYS = 2;

    private final Map<Ccy, Holidays> calendars;

    public DateRules(Map<Ccy, Holidays> calendars) {
        Objects.requireNonNull(calendars, "calendars");
        this.calendars = Map.copyOf(calendars);
    }

    public LocalDate spotDate(LocalDate tradeDate, Pair pair) {
        Objects.requireNonNull(tradeDate, "tradeDate");
        Objects.requireNonNull(pair, "pair");
        requireCalendar(pair.base());
        requireCalendar(pair.counter());
        LocalDate cursor = tradeDate;
        int businessSteps = 0;
        while (businessSteps < SPOT_LAG_DAYS) {
            cursor = cursor.plusDays(1);
            if (isBusinessDayForBoth(cursor, pair)) {
                businessSteps++;
            }
        }
        return cursor;
    }

    public LocalDate deliveryDate(LocalDate spot, Pair pair, Schedule schedule) {
        Objects.requireNonNull(spot, "spot");
        Objects.requireNonNull(pair, "pair");
        Objects.requireNonNull(schedule, "schedule");
        if (schedule instanceof Schedule.Standard standard) {
            LocalDate unadjusted = shiftCalendarMonths(spot, standard.tenorCode());
            if (unadjusted.isBefore(spot)) {
                throw new FxException(Failure.DATE_ORDER,
                        "tenor " + standard.tenorCode() + " resolves before spot " + spot);
            }
            if (!isBusinessDayForBoth(unadjusted, pair)) {
                unadjusted = modifiedFollowing(unadjusted, pair);
            }
            return unadjusted;
        }
        Schedule.Broken broken = (Schedule.Broken) schedule;
        LocalDate delivery = broken.deliveryDate();
        if (delivery.isBefore(spot)) {
            throw new FxException(Failure.DATE_ORDER,
                    "broken delivery date " + delivery + " precedes spot " + spot);
        }
        if (!isBusinessDayForBoth(delivery, pair)) {
            throw new FxException(Failure.DATE_ORDER,
                    "broken delivery date " + delivery + " is not a business day for " + pair);
        }
        return delivery;
    }

    private void requireCalendar(Ccy ccy) {
        if (!calendars.containsKey(ccy)) {
            throw new FxException(Failure.INVALID_CURRENCY,
                    "no holiday calendar registered for " + ccy);
        }
    }

    private boolean isBusinessDayForBoth(LocalDate date, Pair pair) {
        return calendars.get(pair.base()).isBusinessDay(date)
                && calendars.get(pair.counter()).isBusinessDay(date);
    }

    /**
     * Modified following: move forward to a common business day, but stay in
     * the same month; if that would cross a month boundary, step backward once.
     * The single forward walk performs exactly one adjustment (no re-rolling).
     */
    private LocalDate modifiedFollowing(LocalDate date, Pair pair) {
        LocalDate forward = date;
        while (!isBusinessDayForBoth(forward, pair)) {
            forward = forward.plusDays(1);
        }
        if (forward.getMonth() != date.getMonth()) {
            LocalDate backward = date;
            while (!isBusinessDayForBoth(backward, pair)) {
                backward = backward.minusDays(1);
            }
            return backward;
        }
        return forward;
    }

    private LocalDate shiftCalendarMonths(LocalDate spot, String tenorCode) {
        return switch (tenorCode) {
            case "ON" -> spot;
            case "1W" -> spot.plusWeeks(1);
            case "2W" -> spot.plusWeeks(2);
            case "1M" -> spot.plusMonths(1);
            case "3M" -> spot.plusMonths(3);
            case "6M" -> spot.plusMonths(6);
            case "9M" -> spot.plusMonths(9);
            case "1Y" -> spot.plusYears(1);
            default -> throw new FxException(Failure.ILLEGAL_TENOR,
                    "unsupported standard tenor: " + tenorCode);
        };
    }

    static LocalDate parseIsoDate(String text) {
        try {
            return LocalDate.parse(text.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (RuntimeException ex) {
            throw new FxException(Failure.FIXTURE_ERROR, "invalid ISO date: " + text);
        }
    }
}
