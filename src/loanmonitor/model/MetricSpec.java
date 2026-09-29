package loanmonitor.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Declarative metric definition: how to compute one value for one test
 * period, with explicitly declared period basis, annualization and rounding
 * order. {@code formula} is interpreted by the direct formula interpreter.
 * {@code maxIntegerDigits} (0 = unlimited) bounds the unscaled integer part
 * of the fixed-scale result; exceeding it is classified as OVERFLOW.
 */
public record MetricSpec(
        String name,
        Unit resultUnit,
        PeriodBasis basis,
        LocalDate inception,
        Annualization annualization,
        RoundingOrder roundingOrder,
        int resultScale,
        FormulaNode formula,
        int maxIntegerDigits) {

    public MetricSpec(String name, Unit resultUnit, PeriodBasis basis,
                      LocalDate inception, Annualization annualization,
                      RoundingOrder roundingOrder, int resultScale,
                      FormulaNode formula) {
        this(name, resultUnit, basis, inception, annualization, roundingOrder,
                resultScale, formula, 0);
    }

    public static final BigDecimal ZERO = BigDecimal.ZERO;
}
