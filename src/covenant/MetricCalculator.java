package covenant;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class MetricCalculator {

    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final int WORKING_SCALE = 20;
    private static final BigDecimal OVERFLOW_LIMIT = new BigDecimal("1E18");

    private final FactStore store;
    private final MetricCatalog catalog;

    public MetricCalculator(FactStore store, MetricCatalog catalog) {
        if (store == null || catalog == null) {
            throw new InvalidInputException("store and catalog must not be null");
        }
        this.store = store;
        this.catalog = catalog;
    }

    public ExplainedValue calculate(String borrower, MetricSpec spec,
                                    LocalDate periodStart, LocalDate periodEnd) {
        if (periodEnd == null || periodStart == null || !periodEnd.isAfter(periodStart)) {
            throw new InvalidInputException("period end must be strictly after start");
        }
        Context ctx = new Context();
        try {
            BigDecimal raw = eval(borrower, spec.definition(), periodStart, periodEnd,
                    spec.periodBasis(), new LinkedHashSet<>(), ctx);
            BigDecimal preAnnualized = spec.roundingOrder() == RoundingOrder.ROUND_BEFORE_ANNUALIZE
                    ? raw.setScale(spec.scale(), ROUNDING)
                    : raw;
            BigDecimal annualized = applyAnnualization(preAnnualized, spec,
                    periodStart, periodEnd);
            BigDecimal scaled = annualized.setScale(spec.scale(), ROUNDING);
            guardOverflow(scaled, spec.id());
            String tree = spec.id() + "{" + spec.periodBasis() + "," + spec.annualization()
                    + "," + spec.roundingOrder() + ",scale=" + spec.scale() + "} = "
                    + scaled.toPlainString() + " :: " + ctx.tree;
            return new ExplainedValue(scaled,
                    ctx.sources.stream().sorted(FactStore.canonicalOrder).toList(),
                    tree);
        } catch (ArithmeticException e) {
            throw new OverflowException("arithmetic overflow in metric " + spec.id()
                    + ": " + e.getMessage());
        }
    }

    private BigDecimal eval(String borrower, Expr expr,
                            LocalDate anchorStart, LocalDate anchorEnd,
                            PeriodBasis selectionBasis,
                            Set<String> stack, Context ctx) {
        if (expr instanceof Expr.Literal lit) {
            ctx.tree += lit.value().toPlainString();
            return lit.value();
        }
        if (expr instanceof Expr.FactRef ref) {
            return evalRef(borrower, ref.metricId(), anchorStart, anchorEnd,
                    selectionBasis, stack, ctx);
        }
        Expr.BinaryOp op = (Expr.BinaryOp) expr;
        ctx.tree += "(";
        BigDecimal left = eval(borrower, op.left(), anchorStart, anchorEnd,
                selectionBasis, stack, ctx);
        ctx.tree += " " + symbol(op.operator()) + " ";
        BigDecimal right = eval(borrower, op.right(), anchorStart, anchorEnd,
                selectionBasis, stack, ctx);
        ctx.tree += ")";
        return applyOperator(op.operator(), left, right);
    }

    private BigDecimal evalRef(String borrower, String metricId,
                               LocalDate anchorStart, LocalDate anchorEnd,
                               PeriodBasis selectionBasis,
                               Set<String> stack, Context ctx) {
        if (!stack.add(metricId)) {
            throw new InvalidInputException("circular metric reference: " + metricId);
        }
        MetricSpec referenced = catalog.get(metricId);
        // The period basis is inherited from the referencing (parent) metric so that
        // rolling/point/cumulative semantics are never mixed within one expression.
        List<Fact> picked = selectFacts(borrower, referenced, selectionBasis,
                anchorStart, anchorEnd);
        if (picked.isEmpty()) {
            throw new MissingDefinitionException(
                    "missing fact: borrower=" + borrower + " metric=" + metricId
                            + " period=" + Periods.label(anchorStart, anchorEnd));
        }
        if (!picked.get(0).unit().compatibleWith(referenced.unit())) {
            throw new UnitConflictException("unit conflict in fact for metric " + metricId
                    + ": declared " + referenced.unit().name() + " but fact is "
                    + picked.get(0).unit().name());
        }
        BigDecimal combined;
        ctx.tree += metricId + "[" + referenced.periodBasis() + ":";
        BigDecimal sum = BigDecimal.ZERO;
        boolean first = true;
        for (Fact fact : picked) {
            sum = sum.add(fact.value());
            ctx.sources.add(fact);
            if (!first) {
                ctx.tree += "+";
            }
            ctx.tree += fact.source() + "=" + fact.value().toPlainString();
            first = false;
        }
        combined = finishRaw(referenced, sum);
        ctx.tree += "=>" + combined.toPlainString() + "]";
        stack.remove(metricId);
        return combined;
    }

    private BigDecimal finishRaw(MetricSpec spec, BigDecimal raw) {
        guardOverflow(raw, spec.id());
        return raw.setScale(WORKING_SCALE, ROUNDING);
    }

    private List<Fact> selectFacts(String borrower, MetricSpec spec,
                                   PeriodBasis basis,
                                   LocalDate periodStart, LocalDate periodEnd) {
        return switch (basis) {
            case POINT_IN_TIME -> store.find(borrower, spec.id(), periodStart, periodEnd)
                    .map(List::of).orElseGet(List::of);
            case CUMULATIVE -> store.inWindow(borrower, spec.id(), periodStart, periodEnd);
            case ROLLING_12 -> store.inWindow(borrower, spec.id(),
                    Periods.rolling12Start(periodEnd), periodEnd);
        };
    }

    private BigDecimal applyOperator(Operator operator, BigDecimal left, BigDecimal right) {
        BigDecimal result = switch (operator) {
            case PLUS -> left.add(right);
            case MINUS -> left.subtract(right);
            case MULTIPLY -> left.multiply(right, new MathContext(WORKING_SCALE * 2, ROUNDING));
            case DIVIDE -> {
                if (right.signum() == 0) {
                    throw new DivisionByZeroException("division by zero in formula");
                }
                yield left.divide(right, WORKING_SCALE, ROUNDING);
            }
        };
        guardOverflow(result, "formula");
        return result;
    }

    private BigDecimal applyAnnualization(BigDecimal raw, MetricSpec spec,
                                          LocalDate periodStart, LocalDate periodEnd) {
        BigDecimal factor = switch (spec.annualization()) {
            case NONE -> BigDecimal.ONE;
            case ACT_365F -> {
                long days = java.time.temporal.ChronoUnit.DAYS.between(periodStart, periodEnd);
                yield BigDecimal.valueOf(365L).divide(BigDecimal.valueOf(days),
                        WORKING_SCALE, ROUNDING);
            }
            case ACT_360 -> {
                long days = java.time.temporal.ChronoUnit.DAYS.between(periodStart, periodEnd);
                yield BigDecimal.valueOf(360L).divide(BigDecimal.valueOf(days),
                        WORKING_SCALE, ROUNDING);
            }
            case THIRTY_360 -> {
                long days = thirty360Days(periodStart, periodEnd);
                yield BigDecimal.valueOf(360L).divide(BigDecimal.valueOf(days),
                        WORKING_SCALE, ROUNDING);
            }
        };
        return raw.multiply(factor, new MathContext(WORKING_SCALE * 2, ROUNDING));
    }

    static long thirty360Days(LocalDate start, LocalDate end) {
        int d1 = Math.min(start.getDayOfMonth(), 30);
        int d2 = start.getDayOfMonth() == 31 && end.getDayOfMonth() == 31
                ? 30 : end.getDayOfMonth();
        return 360L * (end.getYear() - start.getYear())
                + 30L * (end.getMonthValue() - start.getMonthValue())
                + (d2 - d1);
    }

    private void guardOverflow(BigDecimal value, String metricId) {
        if (value.abs().compareTo(OVERFLOW_LIMIT) >= 0) {
            throw new OverflowException("metric value overflow for " + metricId
                    + ": abs(value) >= 1E18");
        }
    }

    private static String symbol(Operator operator) {
        return switch (operator) {
            case PLUS -> "+";
            case MINUS -> "-";
            case MULTIPLY -> "*";
            case DIVIDE -> "/";
        };
    }

    private static final class Context {
        private final List<Fact> sources = new ArrayList<>();
        private String tree = "";
    }
}
