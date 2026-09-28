package covenant;

import java.time.LocalDate;

public interface LogicalClock {
    LocalDate today();
}
