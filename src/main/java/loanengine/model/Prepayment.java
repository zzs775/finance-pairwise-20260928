package loanengine.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Prepayment(LocalDate valueDate, BigDecimal amount, RecastMode mode) {

    public enum RecastMode {
        SHORTEN_TERM,
        KEEP_TERM
    }
}
