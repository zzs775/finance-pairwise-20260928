package csm.engine;

import csm.model.Amount;
import csm.model.BalanceTriple;
import csm.model.JournalEntry;
import csm.model.PeriodInput;
import csm.model.RuleVersion;
import csm.model.StepResult;
import csm.model.StepType;

import java.util.ArrayList;
import java.util.List;

/** 步骤一：利息展开（FCF 与 CSM 按期初与适用利率计提）。 */
public final class StepAccretion {

    private final FrozenRules rules;
    private final Journal journal;

    public StepAccretion(FrozenRules rules, Journal journal) {
        this.rules = rules;
        this.journal = journal;
    }

    public StepResult apply(PeriodInput input,
                            csm.model.OpeningBalances opening) {
        RuleVersion version = rules.resolve(input.ruleVersionCode());

        Amount fcfAccretion = opening.fulfillmentCashFlow()
                .multiplyRate(rules.fcfAccretionRate(input).raw());
        Amount csmAccretion = opening.csm()
                .multiplyRate(rules.csmAccretionRate(version, input).raw());

        List<JournalEntry> entries = new ArrayList<>();
        if (fcfAccretion.isPositive()) {
            entries.add(journal.entry("accretion.fcf", List.of(
                    journal.debit(Accounts.PNL_INSURANCE_FINANCE, fcfAccretion),
                    journal.credit(Accounts.FCF_LIABILITY, fcfAccretion))));
        }
        if (csmAccretion.isPositive()) {
            entries.add(journal.entry("accretion.csm", List.of(
                    journal.debit(Accounts.PNL_INSURANCE_FINANCE, csmAccretion),
                    journal.credit(Accounts.CSM_LIABILITY, csmAccretion))));
        }

        return new StepResult(
                StepType.INTEREST_ACCRETION,
                BalanceTriple.roll(opening.fulfillmentCashFlow(), fcfAccretion),
                BalanceTriple.roll(opening.csm(), csmAccretion),
                BalanceTriple.openingOnly(opening.lossComponent()),
                List.copyOf(entries));
    }
}
