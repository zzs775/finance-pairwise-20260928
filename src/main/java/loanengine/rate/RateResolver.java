package loanengine.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

public final class RateResolver {

    public static final int RATE_SCALE = 10;

    private RateResolver() {
    }

    public static ResolvedRate resolve(ResetSpec reset, LocalDate resetDate) {
        if (!reset.isFloating()) {
            return new ResolvedRate(reset.floor().setScale(RATE_SCALE, RoundingMode.HALF_UP), 0);
        }
        FixingSeries series = reset.fixings();
        BigDecimal raw = series.rateAt(resetDate);
        BigDecimal rate = raw.add(reset.spread());
        if (reset.floor() != null) {
            rate = rate.max(reset.floor());
        }
        if (reset.cap() != null) {
            rate = rate.min(reset.cap());
        }
        return new ResolvedRate(rate.setScale(RATE_SCALE, RoundingMode.HALF_UP),
                series.versionAt(resetDate));
    }
}
