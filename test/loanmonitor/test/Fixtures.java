package loanmonitor.test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import loanmonitor.MonitoringEngine;
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

/** Shared synthetic (entirely fictional) builders for tests. */
final class Fixtures {

    static final String BORROWER = "NORTHWIND_FICTIONAL_LTD";
    static final LocalDate INCEPTION = LocalDate.of(2024, 1, 1);

    private Fixtures() {
    }

    static TestPeriod period(String label, String start, String end) {
        return new TestPeriod(label, LocalDate.parse(start), LocalDate.parse(end));
    }

    static TestPeriod quarter(String label, int quarter) {
        int startMonth = (quarter - 1) * 3 + 1;
        LocalDate start = LocalDate.of(2024, startMonth, 1);
        LocalDate end = start.plusMonths(3).minusDays(1);
        return new TestPeriod(label, start, end);
    }

    static Fact flow(String id, String account, String value, Unit unit,
                     String start, String end) {
        return new Fact(id, BORROWER, account, new BigDecimal(value), unit,
                LocalDate.parse(start), LocalDate.parse(end), null, 1);
    }

    static Fact flowV(String id, String account, String value, Unit unit,
                      String start, String end, int version) {
        return new Fact(id, BORROWER, account, new BigDecimal(value), unit,
                LocalDate.parse(start), LocalDate.parse(end), null, version);
    }

    static Fact stock(String id, String account, String value, Unit unit,
                      String asOf) {
        return new Fact(id, BORROWER, account, new BigDecimal(value), unit,
                null, null, LocalDate.parse(asOf), 1);
    }

    static Fact stockV(String id, String account, String value, Unit unit,
                       String asOf, int version) {
        return new Fact(id, BORROWER, account, new BigDecimal(value), unit,
                null, null, LocalDate.parse(asOf), version);
    }

    static PaymentEvent payment(String id, String schedule, String amount,
                                String due, String paid) {
        return new PaymentEvent(id, BORROWER, schedule, new BigDecimal(amount),
                Unit.USD, LocalDate.parse(due),
                paid == null ? null : LocalDate.parse(paid));
    }

    static MetricSpec metric(String name, PeriodBasis basis,
                             Annualization annualization,
                             RoundingOrder rounding, int scale,
                             FormulaNode formula, Unit resultUnit) {
        return new MetricSpec(name, resultUnit, basis, null, annualization,
                rounding, scale, formula);
    }

    static FormulaNode ref(String account, Unit unit) {
        return new FormulaNode.FactRef(account, unit);
    }

    static FormulaNode ratio(String numerator, String denominator) {
        return new FormulaNode.BinaryOp(FormulaNode.Operator.DIVIDE,
                new FormulaNode.FactRef(numerator, Unit.USD),
                new FormulaNode.FactRef(denominator, Unit.USD));
    }

    static CovenantDefinition covenant(String id, TestType type, MetricSpec spec,
                                       String threshold, int graceDays) {
        return new CovenantDefinition(id, BORROWER, type, spec,
                new BigDecimal(threshold), graceDays);
    }

    static Waiver waiver(String id, String covenantId, List<String> periods,
                         String effective, String expiry) {
        return new Waiver(id, covenantId, java.util.Set.copyOf(periods),
                LocalDate.parse(effective), LocalDate.parse(expiry));
    }

    static MonitoringEngine engine(List<TestPeriod> periods,
                                   List<CovenantDefinition> covenants,
                                   List<Fact> facts,
                                   List<PaymentEvent> payments,
                                   List<Waiver> waivers) {
        return new MonitoringEngine(INCEPTION, periods, covenants, facts,
                payments, waivers);
    }
}
