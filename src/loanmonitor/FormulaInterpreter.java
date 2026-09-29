package loanmonitor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import loanmonitor.model.FormulaNode;
import loanmonitor.model.FormulaNode.BinaryOp;
import loanmonitor.model.FormulaNode.FactRef;
import loanmonitor.model.FormulaNode.Literal;
import loanmonitor.model.MetricResult.SourceRef;
import loanmonitor.model.Unit;

/**
 * Direct (non-compiled) interpreter of {@link FormulaNode} trees against the
 * facts selected for one test period. Produces a value plus a full trace
 * (sources and formula steps) used for provenance and canonical output.
 */
public final class FormulaInterpreter {

    public record Outcome(
            BigDecimal value,
            Unit unit,
            List<SourceRef> sources,
            List<String> traceLines) {
    }

    public record Window(LocalDate start, LocalDate end, String role) {
    }

    public FormulaInterpreter() {
    }

    public Outcome evaluate(FormulaNode node, EvalScope scope) {
        int intermediateScale = scope instanceof EvalContext context
                ? context.intermediateScale() : Integer.MIN_VALUE;
        return evaluate(node, scope, intermediateScale);
    }

    private Outcome evaluate(FormulaNode node, EvalScope scope, int scale) {
        return switch (node) {
            case FactRef ref -> evaluateFactRef(ref, scope, scale);
            case Literal lit -> new Outcome(rounded(lit.value().stripTrailingZeros(), scale),
                    lit.unit(), List.of(), List.of());
            case BinaryOp op -> evaluateBinary(op, scope, scale);
            case FormulaNode.Annualize ann ->
                    throw new CovenantException(CovenantException.Code.MISSING_DEFINITION,
                            "annualization is applied by MetricCalculator, not the tree");
        };
    }

    private Outcome evaluateFactRef(FactRef ref, EvalScope scope, int scale) {
        List<EvalScope.ResolvedFact> resolved =
                scope.factsFor(ref.account(), ref.expectedUnit());
        BigDecimal total = BigDecimal.ZERO;
        List<SourceRef> sources = new java.util.ArrayList<>();
        for (EvalScope.ResolvedFact fact : resolved) {
            if (!fact.unit().compatibleWith(ref.expectedUnit())) {
                throw new CovenantException(CovenantException.Code.UNIT_CONFLICT,
                        "fact " + fact.factId() + " unit " + fact.unit()
                                + " incompatible with declared " + ref.expectedUnit()
                                + " for account " + ref.account());
            }
            total = safeAdd(total, rounded(fact.value(), scale));
            sources.add(new SourceRef(fact.factId(), fact.version(), fact.account(),
                    fact.value(), fact.unit(), "operand"));
        }
        total = rounded(total, scale);
        String trace = ref.account() + "=" + total.toPlainString()
                + " (" + resolved.size() + " fact" + (resolved.size() == 1 ? "" : "s")
                + ")";
        return new Outcome(total, ref.expectedUnit(),
                List.copyOf(sources), List.of(trace));
    }

    private Outcome evaluateBinary(BinaryOp op, EvalScope scope, int scale) {
        Outcome left = evaluate(op.left(), scope, scale);
        Outcome right = evaluate(op.right(), scope, scale);
        List<SourceRef> sources = new java.util.ArrayList<>(left.sources());
        sources.addAll(right.sources());
        List<String> trace = new java.util.ArrayList<>(left.traceLines());
        trace.addAll(right.traceLines());

        BigDecimal value;
        Unit unit;
        switch (op.operator()) {
            case ADD -> {
                requireCompatible(op, left.unit(), right.unit());
                value = safeAdd(left.value(), right.value());
                unit = left.unit();
            }
            case SUBTRACT -> {
                requireCompatible(op, left.unit(), right.unit());
                value = safeSubtract(left.value(), right.value());
                unit = left.unit();
            }
            case MULTIPLY -> {
                value = safeMultiply(left.value(), right.value());
                unit = multiplyUnits(left.unit(), right.unit());
            }
            case DIVIDE -> {
                if (right.value().signum() == 0) {
                    throw new CovenantException(CovenantException.Code.DIVISION_BY_ZERO,
                            "division by zero in denominator of formula");
                }
                value = safeDivide(left.value(), right.value());
                unit = divideUnits(left.unit(), right.unit());
            }
            default -> throw new CovenantException(
                    CovenantException.Code.MISSING_DEFINITION,
                    "unknown operator " + op.operator());
        }
        value = rounded(value, scale);
        trace.add(op.operator() + "(" + left.value().toPlainString() + ","
                + right.value().toPlainString() + ")=" + value.toPlainString());
        return new Outcome(value, unit, List.copyOf(sources), List.copyOf(trace));
    }

    private static BigDecimal rounded(BigDecimal value, int scale) {
        if (scale == Integer.MIN_VALUE) {
            return value;
        }
        try {
            return value.setScale(scale, java.math.RoundingMode.HALF_UP);
        } catch (ArithmeticException e) {
            throw new CovenantException(CovenantException.Code.OVERFLOW,
                    "result " + value.toPlainString()
                            + " cannot be represented at fixed scale " + scale);
        }
    }

    private static void requireCompatible(BinaryOp op, Unit a, Unit b) {
        if (!a.compatibleWith(b)) {
            throw new CovenantException(CovenantException.Code.UNIT_CONFLICT,
                    "cannot apply " + op.operator() + " to units " + a + " and " + b);
        }
    }

    private static Unit multiplyUnits(Unit a, Unit b) {
        if (a == Unit.RATIO) {
            return b;
        }
        if (b == Unit.RATIO) {
            return a;
        }
        throw new CovenantException(CovenantException.Code.UNIT_CONFLICT,
                "cannot multiply dimensional units " + a + " * " + b);
    }

    private static Unit divideUnits(Unit numerator, Unit denominator) {
        if (numerator == denominator) {
            return Unit.RATIO;
        }
        if (denominator == Unit.RATIO) {
            return numerator;
        }
        throw new CovenantException(CovenantException.Code.UNIT_CONFLICT,
                "cannot divide units " + numerator + " / " + denominator);
    }

    static BigDecimal safeAdd(BigDecimal a, BigDecimal b) {
        try {
            return a.add(b);
        } catch (ArithmeticException e) {
            throw overflow(e);
        }
    }

    static BigDecimal safeSubtract(BigDecimal a, BigDecimal b) {
        try {
            return a.subtract(b);
        } catch (ArithmeticException e) {
            throw overflow(e);
        }
    }

    static BigDecimal safeMultiply(BigDecimal a, BigDecimal b) {
        try {
            return a.multiply(b);
        } catch (ArithmeticException e) {
            throw overflow(e);
        }
    }

    static BigDecimal safeDivide(BigDecimal a, BigDecimal b) {
        try {
            return a.divide(b, java.math.MathContext.DECIMAL128);
        } catch (ArithmeticException e) {
            throw overflow(e);
        }
    }

    private static CovenantException overflow(ArithmeticException e) {
        return new CovenantException(CovenantException.Code.OVERFLOW,
                "arithmetic overflow: " + e.getMessage());
    }

    /** Fact lookup boundary; implemented by the calculator per period basis. */
    public interface EvalScope {
        List<ResolvedFact> factsFor(String account, Unit expectedUnit);

        record ResolvedFact(String factId, int version, String account,
                            BigDecimal value, Unit unit) {
        }
    }

    /** Extended scope carrying the declared intermediate rounding scale. */
    public interface EvalContext extends EvalScope {
        int intermediateScale();
    }
}
