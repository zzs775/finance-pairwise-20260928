package fi;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CashFlow(
        LocalDate paymentDate,
        CashFlowType type,
        BigDecimal amount,
        int periodIndex
) implements Comparable<CashFlow> {

    @Override
    public int compareTo(CashFlow other) {
        int byDate = paymentDate.compareTo(other.paymentDate);
        if (byDate != 0) {
            return byDate;
        }
        int byPeriod = Integer.compare(periodIndex, other.periodIndex);
        if (byPeriod != 0) {
            return byPeriod;
        }
        return Integer.compare(type.ordinal(), other.type.ordinal());
    }
}
