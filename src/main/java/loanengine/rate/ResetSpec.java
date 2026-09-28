package loanengine.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ResetSpec(
        List<LocalDate> resetDates,
        FixingSeries fixings,
        BigDecimal spread,
        BigDecimal floor,
        BigDecimal cap) {

    public ResetSpec {
        resetDates = resetDates == null ? null : List.copyOf(resetDates);
        spread = spread == null ? BigDecimal.ZERO : spread;
        if (floor != null && cap != null && floor.compareTo(cap) > 0) {
            throw new IllegalArgumentException("reset floor above cap");
        }
    }

    public static ResetSpec fixed(BigDecimal rate) {
        return new ResetSpec(List.of(), null, BigDecimal.ZERO, rate, rate);
    }

    public boolean isFloating() {
        return fixings != null && resetDates != null && !resetDates.isEmpty();
    }
}
