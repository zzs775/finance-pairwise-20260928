package covenant;

public record MetricSpec(String id,
                         Unit unit,
                         PeriodBasis periodBasis,
                         Annualization annualization,
                         RoundingOrder roundingOrder,
                         int scale,
                         Expr definition) {
}
