package fx;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Zero-coupon discount-factor curve for one currency, anchored at a value date. */
public record DfCurve(Ccy ccy, LocalDate valueDate, List<Node> nodes) {

    private static final BigDecimal YEAR_DAYS = new BigDecimal(365);
    private static final BigDecimal LN2 = new BigDecimal("0.69314718055994530942");
    private static final BigDecimal SERIES_EPS = new BigDecimal("1E-18");
    private static final MathContext MC = Scales.MC;

    public DfCurve {
        Objects.requireNonNull(ccy, "ccy");
        Objects.requireNonNull(valueDate, "valueDate");
        Objects.requireNonNull(nodes, "nodes");
        List<Node> copy = new ArrayList<>(nodes);
        if (copy.isEmpty()) {
            throw new FxException(Failure.CURVE_HOLE, "curve has no nodes: " + ccy);
        }
        for (int i = 0; i < copy.size(); i++) {
            Node node = copy.get(i);
            if (node == null || node.date() == null || node.df() == null) {
                throw new FxException(Failure.CURVE_HOLE,
                        "curve contains a null node/date/df at index " + i + ": " + ccy);
            }
            if (node.df().signum() <= 0) {
                throw new FxException(Failure.NEGATIVE_DISCOUNT_FACTOR,
                        ccy + " df at " + node.date() + " is not positive: " + node.df());
            }
            if (i > 0 && !node.date().isAfter(copy.get(i - 1).date())) {
                throw new FxException(Failure.DATE_ORDER,
                        ccy + " nodes must be strictly ascending at index " + i);
            }
        }
        if (copy.get(0).date().isBefore(valueDate)) {
            throw new FxException(Failure.DATE_ORDER,
                    ccy + " first node predates the curve value date " + valueDate);
        }
        nodes = Collections.unmodifiableList(copy);
    }

    public record Node(LocalDate date, BigDecimal df) {
    }

    public BigDecimal discountFactor(LocalDate date) {
        return Scales.df(rawDiscountFactor(date));
    }

    /** Full-precision discount factor used by valuation arithmetic. */
    public BigDecimal rawDiscountFactor(LocalDate date) {
        Objects.requireNonNull(date, "date");
        if (date.isBefore(valueDate)) {
            throw new FxException(Failure.DATE_ORDER,
                    ccy + " query date " + date + " precedes value date " + valueDate);
        }
        LocalDate first = nodes.get(0).date();
        LocalDate last = nodes.get(nodes.size() - 1).date();
        if (date.isBefore(first) || date.isAfter(last)) {
            throw new FxException(Failure.CURVE_HOLE,
                    ccy + " date " + date + " lies outside curve span [" + first + ", " + last
                            + "]; extrapolation is not allowed");
        }
        for (Node node : nodes) {
            if (date.equals(node.date())) {
                return node.df();
            }
        }
        Node lo = null;
        Node hi = null;
        for (int i = 1; i < nodes.size(); i++) {
            if (date.isAfter(nodes.get(i - 1).date()) && date.isBefore(nodes.get(i).date())) {
                lo = nodes.get(i - 1);
                hi = nodes.get(i);
                break;
            }
        }
        BigDecimal t = yearFraction(valueDate, date);
        BigDecimal t1 = yearFraction(valueDate, lo.date());
        BigDecimal t2 = yearFraction(valueDate, hi.date());
        BigDecimal g1 = ln(lo.df());
        BigDecimal g2 = ln(hi.df());
        BigDecimal weight = t.subtract(t1, MC).divide(t2.subtract(t1, MC), MC);
        BigDecimal g = g1.add(weight.multiply(g2.subtract(g1, MC), MC), MC);
        return exp(g);
    }

    public void validate() {
        // All validation is performed in the canonical constructor.
    }

    static BigDecimal yearFraction(LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to);
        return new BigDecimal(days).divide(YEAR_DAYS, MC);
    }

    /** Deterministic natural logarithm implemented purely with BigDecimal. */
    static BigDecimal ln(BigDecimal x) {
        if (x.signum() <= 0) {
            throw new FxException(Failure.ARITHMETIC_OVERFLOW, "ln of non-positive value");
        }
        long k = 0;
        BigDecimal m = x;
        BigDecimal sqrt2 = new BigDecimal("1.4142135623730950488");
        BigDecimal invSqrt2 = BigDecimal.ONE.divide(sqrt2, MC);
        while (m.compareTo(sqrt2) > 0) {
            m = m.divide(BigDecimal.valueOf(2), MC);
            k++;
        }
        while (m.compareTo(invSqrt2) < 0) {
            m = m.multiply(BigDecimal.valueOf(2), MC);
            k--;
        }
        BigDecimal u = m.subtract(BigDecimal.ONE, MC)
                .divide(m.add(BigDecimal.ONE, MC), MC);
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal u2 = u.multiply(u, MC);
        BigDecimal term = u;
        long n = 1;
        while (term.abs().compareTo(SERIES_EPS) > 0) {
            sum = sum.add(term.divide(BigDecimal.valueOf(n), MC), MC);
            term = term.multiply(u2, MC);
            n += 2;
        }
        BigDecimal lnM = sum.add(sum, MC);
        return lnM.add(LN2.multiply(BigDecimal.valueOf(k), MC), MC);
    }

    /** Deterministic exponential implemented purely with BigDecimal (Taylor series). */
    static BigDecimal exp(BigDecimal x) {
        if (x.abs().compareTo(new BigDecimal("10")) > 0) {
            throw new FxException(Failure.ARITHMETIC_OVERFLOW,
                    "exp argument out of supported range: " + x.toPlainString());
        }
        BigDecimal sum = BigDecimal.ONE;
        BigDecimal term = BigDecimal.ONE;
        for (long n = 1; n <= 200; n++) {
            term = term.multiply(x, MC).divide(BigDecimal.valueOf(n), MC);
            sum = sum.add(term, MC);
            if (term.abs().compareTo(SERIES_EPS) < 0) {
                break;
            }
        }
        return sum;
    }
}
