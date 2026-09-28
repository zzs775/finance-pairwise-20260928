package fx;

import java.time.LocalDate;
import java.util.Set;

/** Settlement schedule: either a standard tenor or an explicit broken date. */
public sealed interface Schedule permits Schedule.Standard, Schedule.Broken {

    Set<String> STANDARD_TENORS =
            Set.of("ON", "1W", "2W", "1M", "3M", "6M", "9M", "1Y");

    record Standard(String tenorCode) implements Schedule {
        public Standard {
            if (tenorCode == null || !STANDARD_TENORS.contains(tenorCode)) {
                throw new FxException(Failure.ILLEGAL_TENOR,
                        "unsupported standard tenor: " + tenorCode);
            }
        }
    }

    record Broken(LocalDate deliveryDate) implements Schedule {
        public Broken {
            if (deliveryDate == null) {
                throw new FxException(Failure.DATE_ORDER, "broken date must not be null");
            }
        }
    }
}
