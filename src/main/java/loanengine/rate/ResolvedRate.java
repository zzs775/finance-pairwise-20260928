package loanengine.rate;

import java.math.BigDecimal;

public record ResolvedRate(BigDecimal rate, int version) {
}
