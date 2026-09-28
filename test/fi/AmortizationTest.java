package fi;

import java.math.BigDecimal;
import java.util.List;

public final class AmortizationTest {

    private AmortizationTest() {
    }

    static void run(Tests.Asserts a) {
        CashFlowSchedule schedule =
                ScheduleGenerator.generate(SampleBonds.eomAmortizing());

        a.equal("notional period 1",
                schedule.periods().get(0).notional().toPlainString(),
                "1000000.00");
        a.equal("notional period 3 after step",
                schedule.periods().get(2).notional().toPlainString(),
                "700000.00");
        a.equal("notional period 4 after second step",
                schedule.periods().get(3).notional().toPlainString(),
                "400000.00");

        List<CashFlow> principal = schedule.cashFlows().stream()
                .filter(c -> c.type() == CashFlowType.PRINCIPAL)
                .toList();
        a.equal("two principal payments", principal.size(), 2);
        a.money("first principal", principal.get(0).amount(), "300000.00");
        a.money("second principal", principal.get(1).amount(), "300000.00");

        BigDecimal principalTotal = principal.stream()
                .map(CashFlow::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        a.money("principal total equals initial notional",
                principalTotal, "600000.00");

        for (int i = 1; i < schedule.periods().size(); i++) {
            a.that("notional never rises " + i,
                    schedule.periods().get(i).notional()
                            .compareTo(schedule.periods().get(i - 1).notional()) <= 0);
        }
        for (AccrualPeriod period : schedule.periods()) {
            a.that("notional non-negative",
                    period.notional().signum() >= 0);
        }
    }
}
