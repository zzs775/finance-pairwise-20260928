package csm.engine;

import csm.model.Amount;
import csm.model.BalanceTriple;
import csm.model.ClassificationException;
import csm.model.JournalEntry;
import csm.model.OpeningBalances;
import csm.model.PeriodInput;
import csm.model.StepResult;
import csm.model.StepType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 步骤三：当期服务释放，按覆盖单位比例分配；单位零或负分类失败。 */
public final class StepServiceRelease {

    private final Journal journal;

    public StepServiceRelease(Journal journal) {
        this.journal = journal;
    }

    public StepResult apply(PeriodInput input,
                            OpeningBalances opening) {
        if (!input.currentCoverageUnits().isPositive()
                || !input.remainingCoverageUnits().isPositive()) {
            throw new ClassificationException(
                    "coverage units must be strictly positive for release "
                            + input.groupId() + "/" + input.periodId());
        }

        BigDecimal ratio = input.currentCoverageUnits().raw()
                .divide(input.remainingCoverageUnits().raw(),
                        Amount.RATE_SCALE, Amount.ROUNDING);

        Amount csmRelease = opening.csm().multiplyRate(ratio);
        Amount lcRelease = opening.lossComponent().multiplyRate(ratio);
        List<JournalEntry> entries = new ArrayList<>();
        if (csmRelease.isPositive()) {
            entries.add(journal.entry("service-release.csm", List.of(
                    journal.debit(Accounts.CSM_LIABILITY, csmRelease),
                    journal.credit(Accounts.PNL_INSURANCE_SERVICE,
                            csmRelease))));
        }
        if (lcRelease.isPositive()) {
            entries.add(journal.entry("service-release.fcf", List.of(
                    journal.debit(Accounts.FCF_LIABILITY, lcRelease),
                    journal.credit(Accounts.PNL_INSURANCE_SERVICE,
                            lcRelease))));
            entries.add(journal.entry("service-release.loss", List.of(
                    journal.debit(Accounts.LOSS_COMPONENT_MEMO, lcRelease),
                    journal.credit(Accounts.LOSS_COMPONENT_CONTRA,
                            lcRelease))));
        }

        return new StepResult(
                StepType.SERVICE_RELEASE,
                BalanceTriple.roll(opening.fulfillmentCashFlow(),
                        lcRelease.negate()),
                BalanceTriple.roll(opening.csm(), csmRelease.negate()),
                BalanceTriple.roll(opening.lossComponent(),
                        lcRelease.negate()),
                List.copyOf(entries));
    }
}
