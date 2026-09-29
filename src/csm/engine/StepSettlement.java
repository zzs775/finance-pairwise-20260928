package csm.engine;

import csm.model.BalanceTriple;
import csm.model.JournalEntry;
import csm.model.OpeningBalances;
import csm.model.PeriodInput;
import csm.model.StepResult;
import csm.model.StepType;

import java.util.List;

/** 步骤四：现金流结算，现金流出冲减履约现金流负债。 */
public final class StepSettlement {

    private final Journal journal;

    public StepSettlement(Journal journal) {
        this.journal = journal;
    }

    public StepResult apply(PeriodInput input,
                            OpeningBalances opening) {
        var cash = input.settlement();
        List<JournalEntry> entries = List.of();
        if (cash.isPositive()) {
            entries = List.of(journal.entry("settlement.cash", List.of(
                    journal.debit(Accounts.FCF_LIABILITY, cash),
                    journal.credit(Accounts.CASH, cash))));
        }
        return new StepResult(
                StepType.SETTLEMENT,
                BalanceTriple.roll(opening.fulfillmentCashFlow(), cash.negate()),
                BalanceTriple.openingOnly(opening.csm()),
                BalanceTriple.openingOnly(opening.lossComponent()),
                entries);
    }
}
