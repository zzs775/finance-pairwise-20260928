package loanengine.rate;

import loanengine.core.LoanException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class FixingSeries {

    private final Map<LocalDate, Fixing> latest = new TreeMap<>();

    public FixingSeries(List<Fixing> fixings) {
        if (fixings != null) {
            for (Fixing fixing : fixings) {
                latest.merge(fixing.resetDate(), fixing,
                        (a, b) -> b.version() >= a.version() ? b : a);
            }
        }
    }

    public BigDecimal rateAt(LocalDate resetDate) {
        return fixing(resetDate).rate();
    }

    public int versionAt(LocalDate resetDate) {
        return fixing(resetDate).version();
    }

    private Fixing fixing(LocalDate resetDate) {
        Fixing fixing = latest.get(resetDate);
        if (fixing == null) {
            throw new LoanException(LoanException.Category.MISSING_FIXING,
                    "no fixing published for reset date " + resetDate);
        }
        return fixing;
    }
}
