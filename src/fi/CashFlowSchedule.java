package fi;

import java.util.List;

public record CashFlowSchedule(
        BondTerms terms,
        List<AccrualPeriod> periods,
        List<CashFlow> cashFlows
) {
}
