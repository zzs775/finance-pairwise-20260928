package covenant;

import java.math.BigDecimal;

public sealed interface Expr permits Expr.Literal, Expr.FactRef, Expr.BinaryOp {

    record Literal(BigDecimal value) implements Expr {
    }

    record FactRef(String metricId) implements Expr {
    }

    record BinaryOp(Operator operator, Expr left, Expr right) implements Expr {
    }
}
