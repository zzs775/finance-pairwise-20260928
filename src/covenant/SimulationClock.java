package covenant;

import java.time.LocalDate;

public final class SimulationClock implements LogicalClock {

    private LocalDate date;

    public SimulationClock(LocalDate start) {
        if (start == null) {
            throw new InvalidInputException("clock start must not be null");
        }
        this.date = start;
    }

    @Override
    public LocalDate today() {
        return date;
    }

    public void set(LocalDate date) {
        if (date == null) {
            throw new InvalidInputException("clock date must not be null");
        }
        this.date = date;
    }

    public void advanceDays(int days) {
        this.date = this.date.plusDays(days);
    }
}
