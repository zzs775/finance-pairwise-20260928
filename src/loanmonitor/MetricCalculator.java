package loanmonitor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import loanmonitor.model.Annualization;
import loanmonitor.model.CovenantDefinition;
import loanmonitor.model.Fact;
import loanmonitor.model.FormulaNode;
import loanmonitor.model.MetricResult;
import loanmonitor.model.MetricSpec;
import loanmonitor.model.PeriodBasis;
import loanmonitor.model.RoundingOrder;
import loanmonitor.model.TestPeriod;
import loanmonitor.model.Unit;

/**
 * Computes one {@link MetricResult} for one covenant/period pair: selects
 * facts according to the declared period basis, interprets the formula,
 * applies annualization and the declared rounding order at a fixed scale.
 *
 * Fixed boundary semantics (all date bounds inclusive):
 * POINT_IN_TIME selects stock facts with asOf == periodEnd;
 * ROLLING_12 selects flow facts whose range intersects the window
 * [periodEnd.minusYears(1).plusDays(1), periodEnd] (trailing 365/366 days);
 * CUMULATIVE selects flow facts intersecting [inception, periodEnd].
 */
public final class MetricCalculator {

    static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final FormulaInterpreter interpreter = new FormulaInterpreter();

    public MetricCalculator() {
    }

    public MetricResult calculate(
            CovenantDefinition covenant,
            TestPeriod period,
            Collection<Fact> facts,
            LocalDate inception) {
        MetricSpec spec = covenant.metric();
        if (spec == null) {
            throw new CovenantException(CovenantException.Code.MISSING_DEFINITION,
                    "covenant " + covenant.id() + " has no metric definition");
        }
        LocalDate windowStart;
        LocalDate windowEnd = period.periodEnd();
        List<Fact> selected = new ArrayList<>();
        switch (spec.basis()) {
            case POINT_IN_TIME -> {
                windowStart = windowEnd;
                for (Fact fact : facts) {
                    if (matchesBorrower(fact, covenant) && !fact.isFlow()
                            && fact.asOf().equals(windowEnd)) {
                        selected.add(fact);
                    }
                }
            }
            case ROLLING_12 -> {
                windowStart = windowEnd.minusYears(1).plusDays(1);
                for (Fact fact : facts) {
                    if (matchesBorrower(fact, covenant) && fact.isFlow()
                            && intersects(fact, windowStart, windowEnd)) {
                        selected.add(fact);
                    }
                }
            }
            case CUMULATIVE -> {
                windowStart = inception;
                for (Fact fact : facts) {
                    if (matchesBorrower(fact, covenant) && fact.isFlow()
                            && intersects(fact, windowStart, windowEnd)) {
                        selected.add(fact);
                    }
                }
            }
            default -> throw new CovenantException(
                    CovenantException.Code.MISSING_DEFINITION,
                    "unknown period basis " + spec.basis());
        }

        List<Fact> sorted = new ArrayList<>(selected);
        sorted.sort(java.util.Comparator.comparing(Fact::id)
                .thenComparingInt(Fact::version));

        int intermediateScale = spec.roundingOrder() == RoundingOrder.INTERMEDIATE
                ? spec.resultScale() : Integer.MIN_VALUE;
        FormulaInterpreter.EvalScope scope =
                new PeriodScope(sorted, intermediateScale);
        FormulaInterpreter.Outcome outcome =
                interpreter.evaluate(spec.formula(), scope);

        BigDecimal value = outcome.value();
        List<String> trace = new ArrayList<>(outcome.traceLines());
        if (spec.basis() != PeriodBasis.POINT_IN_TIME
                && spec.annualization() != Annualization.NONE) {
            long elapsedDays = ChronoUnit.DAYS.between(windowStart, windowEnd) + 1;
            if (elapsedDays <= 0) {
                throw new CovenantException(CovenantException.Code.INVALID_PERIOD,
                        "non-positive annualization window for " + covenant.id());
            }
            value = switch (spec.annualization()) {
                case ACTUAL_365 -> annualizeActual365(value, elapsedDays, spec.resultScale());
                case TWELVE_MONTHS -> annualizeTwelveMonths(value, windowStart,
                        windowEnd, spec.resultScale());
                case NONE -> value;
            };
            trace.add("ANNUALIZE(" + spec.annualization() + ",days=" + elapsedDays
                    + ")=" + value.toPlainString());
        }

        if (spec.roundingOrder() == RoundingOrder.FINAL) {
            value = value.setScale(spec.resultScale(), ROUNDING);
            trace.add("ROUND_FINAL(scale=" + spec.resultScale() + ")="
                    + value.toPlainString());
        } else {
            value = value.setScale(spec.resultScale(), ROUNDING);
        }
        if (spec.maxIntegerDigits() > 0) {
            int integerDigits = value.precision() - value.scale();
            if (integerDigits > spec.maxIntegerDigits()) {
                throw new CovenantException(CovenantException.Code.OVERFLOW,
                        "metric " + spec.name() + " value "
                                + value.toPlainString() + " has "
                                + integerDigits + " integer digits, limit "
                                + spec.maxIntegerDigits());
            }
        }

        if (outcome.unit() != spec.resultUnit()) {
            throw new CovenantException(CovenantException.Code.UNIT_CONFLICT,
                    "metric " + spec.name() + " produced unit " + outcome.unit()
                            + " but declared " + spec.resultUnit());
        }

        String traceText = String.join("\n", trace);
        return new MetricResult(covenant.id(), period.label(), spec.name(), value,
                spec.resultUnit(), outcome.sources(), traceText, windowStart, windowEnd);
    }

    private static boolean matchesBorrower(Fact fact, CovenantDefinition covenant) {
        return fact.borrower().equals(covenant.borrower());
    }

    /** Inclusive range intersection. */
    private static boolean intersects(Fact fact, LocalDate start, LocalDate end) {
        return !fact.periodEnd().isBefore(start) && !fact.periodStart().isAfter(end);
    }

    private static BigDecimal annualizeActual365(BigDecimal value, long elapsedDays,
                                                 int scale) {
        return value.multiply(BigDecimal.valueOf(365L))
                .divide(BigDecimal.valueOf(elapsedDays), scale, ROUNDING);
    }

    private static BigDecimal annualizeTwelveMonths(BigDecimal value,
                                                    LocalDate start, LocalDate end,
                                                    int scale) {
        long months = ChronoUnit.MONTHS.between(start, end.plusDays(1));
        if (months <= 0) {
            throw new CovenantException(CovenantException.Code.INVALID_PERIOD,
                    "non-positive month count for TWELVE_MONTHS annualization");
        }
        return value.multiply(BigDecimal.valueOf(12L))
                .divide(BigDecimal.valueOf(months), scale, ROUNDING);
    }

    private static final class PeriodScope
            implements FormulaInterpreter.EvalContext {
        private final List<Fact> facts;
        private final int intermediateScale;

        private PeriodScope(List<Fact> facts, int intermediateScale) {
            this.facts = facts;
            this.intermediateScale = intermediateScale;
        }

        @Override
        public int intermediateScale() {
            return intermediateScale;
        }

        @Override
        public List<ResolvedFact> factsFor(String account, Unit expectedUnit) {
            List<ResolvedFact> matches = new ArrayList<>();
            for (Fact fact : facts) {
                if (fact.account().equals(account)) {
                    matches.add(new ResolvedFact(fact.id(), fact.version(),
                            fact.account(), fact.value(), fact.unit()));
                }
            }
            if (matches.isEmpty()) {
                throw new CovenantException(CovenantException.Code.MISSING_DEFINITION,
                        "no fact found for account '" + account + "' in period window");
            }
            return List.copyOf(matches);
        }
    }
}
