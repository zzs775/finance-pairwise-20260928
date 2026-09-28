package loanengine;

import loanengine.dates.BusinessDayCalendar;
import loanengine.dates.DayConvention;
import loanengine.dates.DayCount;
import loanengine.dates.Frequency;
import loanengine.engine.CashflowEngine;
import loanengine.engine.CashflowRow;
import loanengine.model.Contract;
import loanengine.rate.ResetSpec;
import loanengine.schedule.AmortizationType;
import loanengine.schedule.ScheduleGenerator;
import loanengine.schedule.SchedulePeriod;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static loanengine.TestFramework.check;
import static loanengine.TestFramework.assertEquals;

public final class ScheduleTest {

    private ScheduleTest() {
    }

    public static void run() {
        backwardsAnchoredRegular();
        longFirstPeriod();
        shortFirstPeriod();
        monthEndRule();
        adjustmentDoesNotMoveAccrualBoundaries();
        leapDayAccrual();
    }

    private static Contract.Builder builder(LocalDate value, LocalDate maturity) {
        return Contract.builder()
                .notional(new BigDecimal("12000.00"))
                .valueDate(value)
                .maturityDate(maturity)
                .amortizationType(AmortizationType.EQUAL_PRINCIPAL)
                .frequency(Frequency.QUARTERLY)
                .dayCount(DayCount.ACT_365F)
                .reset(ResetSpec.fixed(new BigDecimal("0.05")));
    }

    private static void backwardsAnchoredRegular() {
        List<SchedulePeriod> periods = ScheduleGenerator.generate(
                builder(LocalDate.of(2025, 1, 15), LocalDate.of(2025, 10, 15)).build());
        assertEquals(3, periods.size(), "three quarterly periods anchored to maturity");
        assertEquals(LocalDate.of(2025, 1, 15), periods.get(0).accrualStart(),
                "first accrual starts at value date");
        assertEquals(LocalDate.of(2025, 4, 15), periods.get(0).accrualEnd(),
                "first period ends Apr 15");
        assertEquals(LocalDate.of(2025, 10, 15),
                periods.get(periods.size() - 1).accrualEnd(),
                "last period ends at maturity");
        for (SchedulePeriod p : periods) {
            check(!p.stub(), "regular periods are not stubs: period " + p.period());
        }
    }

    private static void longFirstPeriod() {
        List<SchedulePeriod> periods = ScheduleGenerator.generate(
                builder(LocalDate.of(2025, 2, 20), LocalDate.of(2025, 7, 15))
                        .firstPaymentDate(LocalDate.of(2025, 4, 15))
                        .build());
        SchedulePeriod first = periods.get(0);
        assertEquals(LocalDate.of(2025, 2, 20), first.accrualStart(),
                "long first starts at value");
        assertEquals(LocalDate.of(2025, 4, 15), first.accrualEnd(),
                "long first ends at first regular anchor");
        check(first.stub(), "long first period flagged as stub");
    }

    private static void shortFirstPeriod() {
        List<SchedulePeriod> periods = ScheduleGenerator.generate(
                builder(LocalDate.of(2025, 3, 10), LocalDate.of(2025, 7, 15))
                        .firstPaymentDate(LocalDate.of(2025, 4, 15))
                        .build());
        SchedulePeriod first = periods.get(0);
        assertEquals(LocalDate.of(2025, 4, 15), first.accrualEnd(),
                "short first ends at first regular anchor");
        check(first.stub(), "short first period flagged as stub");
    }

    private static void monthEndRule() {
        List<SchedulePeriod> periods = ScheduleGenerator.generate(
                builder(LocalDate.of(2025, 1, 31), LocalDate.of(2025, 7, 31))
                        .frequency(Frequency.MONTHLY)
                        .monthEndRule(true)
                        .build());
        assertEquals(LocalDate.of(2025, 2, 28), periods.get(0).accrualEnd(),
                "month-end rule keeps Feb end");
        assertEquals(LocalDate.of(2025, 3, 31), periods.get(1).accrualEnd(),
                "month-end rule restores Mar end");
        check(!periods.get(0).stub() && !periods.get(1).stub(),
                "month-end rule treats adjusted ends as regular");
    }

    private static void adjustmentDoesNotMoveAccrualBoundaries() {
        LocalDate maturity = LocalDate.of(2025, 3, 1);
        BusinessDayCalendar calendar = new BusinessDayCalendar(Set.of(
                LocalDate.of(2025, 3, 3)));
        Contract contract = builder(LocalDate.of(2024, 12, 1), maturity)
                .frequency(Frequency.MONTHLY)
                .dayConvention(DayConvention.FOLLOWING)
                .calendar(calendar)
                .build();
        List<SchedulePeriod> periods = ScheduleGenerator.generate(contract);
        SchedulePeriod last = periods.get(periods.size() - 1);
        assertEquals(LocalDate.of(2025, 3, 1), last.accrualEnd(),
                "accrual end stays at unadjusted maturity");
        assertEquals(LocalDate.of(2025, 3, 4), last.paymentDate(),
                "payment moves to next business day (Sat + holiday)");

        List<CashflowRow> rows = new CashflowEngine().generate(contract);
        CashflowRow lastRow = rows.get(rows.size() - 1);
        assertEquals(LocalDate.of(2025, 3, 1), lastRow.accrualEnd(),
                "cashflow accrual boundary unchanged by adjustment");
        assertEquals(LocalDate.of(2025, 3, 4), lastRow.paymentDate(),
                "cashflow payment date adjusted");
    }

    private static void leapDayAccrual() {
        List<SchedulePeriod> periods = ScheduleGenerator.generate(
                builder(LocalDate.of(2024, 1, 31), LocalDate.of(2024, 3, 31))
                        .frequency(Frequency.MONTHLY)
                        .build());
        assertEquals(LocalDate.of(2024, 2, 29), periods.get(0).accrualEnd(),
                "leap Feb end is the 29th");
        assertEquals(LocalDate.of(2024, 3, 31), periods.get(1).accrualEnd(),
                "second month end");
    }
}
