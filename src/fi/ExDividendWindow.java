package fi;

import java.time.LocalDate;

public record ExDividendWindow(
        int periodIndex,
        LocalDate periodEndUnadjusted,
        LocalDate paymentDate,
        LocalDate exDate
) {
}
