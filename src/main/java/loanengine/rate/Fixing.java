package loanengine.rate;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Fixing(LocalDate resetDate, BigDecimal rate, int version) {
}
