package loanmonitor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import loanmonitor.model.Annualization;
import loanmonitor.model.CovenantDefinition;
import loanmonitor.model.Fact;
import loanmonitor.model.FormulaNode;
import loanmonitor.model.MetricSpec;
import loanmonitor.model.PaymentEvent;
import loanmonitor.model.PeriodBasis;
import loanmonitor.model.RoundingOrder;
import loanmonitor.model.TestPeriod;
import loanmonitor.model.TestType;
import loanmonitor.model.Unit;
import loanmonitor.model.Waiver;

/**
 * Parses the deterministic, line-oriented synthetic input format. Input
 * order never affects output: ordering is canonicalized by the engine.
 *
 * Grammar (one record per line, '#' starts a comment):
 *   meta INCEPTION yyyy-MM-dd
 *   period label yyyy-MM-dd yyyy-MM-dd
 *   fact id borrower account value unit FLOW|STOCK start end asOf version
 *   payment id borrower schedule amount unit dueDate paidDate|.
 *   waiver id covenantId period1,period2 effective expiry
 *   covenant id borrower type threshold graceDays
 *   metric covenantId name resultUnit basis annualization rounding scale formula
 * Formula is an s-expression: account@UNIT, NUMBER[unit], (+ a b),
 * (- a b), (* a b), (/ a b).
 */
public final class Loader {

    public record LoadedInput(
            List<Fact> facts,
            List<PaymentEvent> payments,
            List<CovenantDefinition> covenants,
            List<TestPeriod> periods,
            List<Waiver> waivers,
            LocalDate inception) {
    }

    private record MetricLine(String covenantId, MetricSpec spec) {
    }

    private Loader() {
    }

    public static LoadedInput load(Path inputFile) {
        try {
            return parse(Files.readString(inputFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CovenantException(CovenantException.Code.MISSING_DEFINITION,
                    "cannot read input file: " + e.getMessage());
        }
    }

    public static LoadedInput parse(String text) {
        List<Fact> facts = new ArrayList<>();
        List<PaymentEvent> payments = new ArrayList<>();
        List<TestPeriod> periods = new ArrayList<>();
        List<Waiver> waivers = new ArrayList<>();
        List<String[]> covenantRows = new ArrayList<>();
        List<MetricLine> metricLines = new ArrayList<>();
        LocalDate inception = null;

        int lineNo = 0;
        for (String rawLine : text.split("\n", -1)) {
            lineNo++;
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+");
            try {
                switch (parts[0]) {
                    case "meta" -> inception = parseMeta(parts);
                    case "period" -> periods.add(parsePeriod(parts));
                    case "fact" -> facts.add(parseFact(parts));
                    case "payment" -> payments.add(parsePayment(parts));
                    case "waiver" -> waivers.add(parseWaiver(parts));
                    case "covenant" -> covenantRows.add(parts);
                    case "metric" -> metricLines.add(parseMetric(parts));
                    default -> throw new CovenantException(
                            CovenantException.Code.MISSING_DEFINITION,
                            "unknown record type '" + parts[0] + "'");
                }
            } catch (CovenantException e) {
                throw new CovenantException(e.code(),
                        "line " + lineNo + ": " + e.getMessage());
            }
        }
        if (inception == null) {
            throw new CovenantException(CovenantException.Code.MISSING_DEFINITION,
                    "missing meta INCEPTION record");
        }

        List<CovenantDefinition> covenants = new ArrayList<>();
        for (String[] row : covenantRows) {
            covenants.add(parseCovenant(row, metricLines));
        }
        return new LoadedInput(List.copyOf(facts), List.copyOf(payments),
                List.copyOf(covenants), List.copyOf(periods),
                List.copyOf(waivers), inception);
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    private static LocalDate parseMeta(String[] parts) {
        requireLength(parts, 3, "meta INCEPTION yyyy-MM-dd");
        if (!parts[1].equals("INCEPTION")) {
            throw new CovenantException(CovenantException.Code.MISSING_DEFINITION,
                    "unknown meta key " + parts[1]);
        }
        return LocalDate.parse(parts[2]);
    }

    private static TestPeriod parsePeriod(String[] parts) {
        requireLength(parts, 4, "period label start end");
        return new TestPeriod(parts[1], LocalDate.parse(parts[2]),
                LocalDate.parse(parts[3]));
    }

    private static Fact parseFact(String[] parts) {
        requireLength(parts, 11,
                "fact id borrower account value unit FLOW|STOCK start end asOf version");
        String kind = parts[6];
        LocalDate start = ".".equals(parts[7]) ? null : LocalDate.parse(parts[7]);
        LocalDate end = ".".equals(parts[8]) ? null : LocalDate.parse(parts[8]);
        LocalDate asOf = ".".equals(parts[9]) ? null : LocalDate.parse(parts[9]);
        int version = Integer.parseInt(parts[10]);
        if (version < 1) {
            throw new CovenantException(CovenantException.Code.INVALID_PERIOD,
                    "fact version must be >= 1");
        }
        boolean consistent = switch (kind) {
            case "FLOW" -> start != null && end != null && asOf == null;
            case "STOCK" -> start == null && end == null && asOf != null;
            default -> false;
        };
        if (!consistent) {
            throw new CovenantException(CovenantException.Code.INVALID_PERIOD,
                    "fact date coordinates do not match kind " + kind);
        }
        return new Fact(parts[1], parts[2], parts[3], new BigDecimal(parts[4]),
                parseUnit(parts[5]), start, end, asOf, version);
    }

    private static PaymentEvent parsePayment(String[] parts) {
        requireLength(parts, 8,
                "payment id borrower schedule amount unit due paid|.");
        LocalDate paid = ".".equals(parts[7]) ? null : LocalDate.parse(parts[7]);
        return new PaymentEvent(parts[1], parts[2], parts[3],
                new BigDecimal(parts[4]), parseUnit(parts[5]),
                LocalDate.parse(parts[6]), paid);
    }

    private static Waiver parseWaiver(String[] parts) {
        requireLength(parts, 6,
                "waiver id covenantId p1,p2 effective expiry");
        Set<String> labels = new LinkedHashSet<>(List.of(parts[3].split(",")));
        return new Waiver(parts[1], parts[2], Set.copyOf(labels),
                LocalDate.parse(parts[4]), LocalDate.parse(parts[5]));
    }

    private static CovenantDefinition parseCovenant(
            String[] row, List<MetricLine> metricLines) {
        requireLength(row, 6, "covenant id borrower type threshold graceDays");
        TestType type = TestType.valueOf(row[3]);
        MetricSpec metric = null;
        for (MetricLine line : metricLines) {
            if (line.covenantId().equals(row[1])) {
                if (metric != null) {
                    throw new CovenantException(CovenantException.Code.DUPLICATE_FACT,
                            "multiple metric definitions for covenant " + row[1]);
                }
                metric = line.spec();
            }
        }
        if (type != TestType.PAYMENT_DATE && metric == null) {
            throw new CovenantException(CovenantException.Code.MISSING_DEFINITION,
                    "covenant " + row[1] + " missing metric definition");
        }
        return new CovenantDefinition(row[1], row[2], type, metric,
                new BigDecimal(row[4]), Integer.parseInt(row[5]));
    }

    private static MetricLine parseMetric(String[] parts) {
        if (parts.length < 9) {
            throw new CovenantException(CovenantException.Code.MISSING_DEFINITION,
                    "malformed record, expected: metric cov name unit basis "
                            + "annualization rounding scale formula...");
        }
        String covenantId = parts[1];
        String name = parts[2];
        Unit resultUnit = parseUnit(parts[3]);
        PeriodBasis basis = PeriodBasis.valueOf(parts[4]);
        Annualization annualization = Annualization.valueOf(parts[5]);
        RoundingOrder rounding = RoundingOrder.valueOf(parts[6]);
        int scale = Integer.parseInt(parts[7]);
        String expression = String.join(" ",
                java.util.Arrays.copyOfRange(parts, 8, parts.length));
        FormulaNode formula = new FormulaParser(expression).parse();
        return new MetricLine(covenantId, new MetricSpec(name, resultUnit, basis,
                null, annualization, rounding, scale, formula));
    }

    private static Unit parseUnit(String token) {
        return switch (token) {
            case "USD" -> Unit.USD;
            case "DAYS" -> Unit.DAYS;
            case "COUNT" -> Unit.COUNT;
            case "RATIO" -> Unit.RATIO;
            default -> throw new CovenantException(
                    CovenantException.Code.UNIT_CONFLICT, "unknown unit " + token);
        };
    }

    private static void requireLength(String[] parts, int length, String grammar) {
        if (parts.length != length) {
            throw new CovenantException(CovenantException.Code.MISSING_DEFINITION,
                    "malformed record, expected: " + grammar);
        }
    }

    /** Tiny recursive-descent parser for the s-expression formula grammar. */
    static final class FormulaParser {
        private final String input;
        private int pos;

        FormulaParser(String input) {
            this.input = input;
        }

        FormulaNode parse() {
            FormulaNode node = parseNode();
            skipWhitespace();
            if (pos < input.length()) {
                throw new CovenantException(CovenantException.Code.MISSING_DEFINITION,
                        "trailing characters in formula: " + input.substring(pos));
            }
            return node;
        }

        private FormulaNode parseNode() {
            skipWhitespace();
            if (peek() == '(') {
                return parseList();
            }
            return parseAtom();
        }

        private FormulaNode parseList() {
            expect('(');
            skipWhitespace();
            String op = readToken();
            FormulaNode.Operator operator = switch (op) {
                case "+" -> FormulaNode.Operator.ADD;
                case "-" -> FormulaNode.Operator.SUBTRACT;
                case "*" -> FormulaNode.Operator.MULTIPLY;
                case "/" -> FormulaNode.Operator.DIVIDE;
                default -> throw new CovenantException(
                        CovenantException.Code.MISSING_DEFINITION,
                        "unknown formula operator " + op);
            };
            FormulaNode left = parseNode();
            FormulaNode right = parseNode();
            skipWhitespace();
            expect(')');
            return new FormulaNode.BinaryOp(operator, left, right);
        }

        private FormulaNode parseAtom() {
            String token = readToken();
            int at = token.indexOf('@');
            if (at >= 0) {
                return new FormulaNode.FactRef(token.substring(0, at),
                        parseUnit(token.substring(at + 1)));
            }
            int unitStart = -1;
            for (int i = token.length() - 1; i >= 0; i--) {
                if (Character.isLetter(token.charAt(i))) {
                    unitStart = i;
                } else {
                    break;
                }
            }
            if (unitStart > 0) {
                return new FormulaNode.Literal(
                        new BigDecimal(token.substring(0, unitStart)),
                        parseUnit(token.substring(unitStart)));
            }
            return new FormulaNode.Literal(new BigDecimal(token), Unit.RATIO);
        }

        private String readToken() {
            skipWhitespace();
            int start = pos;
            while (pos < input.length()
                    && !Character.isWhitespace(input.charAt(pos))
                    && input.charAt(pos) != '(' && input.charAt(pos) != ')') {
                pos++;
            }
            if (pos == start) {
                throw new CovenantException(CovenantException.Code.MISSING_DEFINITION,
                        "expected token at position " + pos + " in formula");
            }
            return input.substring(start, pos);
        }

        private void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            skipWhitespace();
            return pos < input.length() ? input.charAt(pos) : '\0';
        }

        private void expect(char c) {
            skipWhitespace();
            if (pos >= input.length() || input.charAt(pos) != c) {
                throw new CovenantException(CovenantException.Code.MISSING_DEFINITION,
                        "expected '" + c + "' in formula");
            }
            pos++;
        }
    }
}
