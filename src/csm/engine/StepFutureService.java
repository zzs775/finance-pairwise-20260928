package csm.engine;

import csm.model.Amount;
import csm.model.BalanceTriple;
import csm.model.JournalEntry;
import csm.model.OpeningBalances;
import csm.model.PeriodInput;
import csm.model.StepResult;
import csm.model.StepType;

import java.util.ArrayList;
import java.util.List;

/**
 * 步骤二：未来服务变动。
 * 不利变动先冲减 CSM（CSM 保持非负），超额进入亏损组成；
 * 后续有利变动先转回亏损组成，再恢复 CSM。
 */
public final class StepFutureService {

    private final Journal journal;

    public StepFutureService(Journal journal) {
        this.journal = journal;
    }

    public StepResult apply(PeriodInput input,
                            OpeningBalances opening) {
        Amount change = input.futureServiceChange();
        Amount fcfMovement = change.negate();

        Amount csmMovement;
        Amount lcMovement;
        List<JournalEntry> entries = new ArrayList<>();

        if (change.isNegative()) {
            Amount unfavourable = change.negate();
            Amount csmReduction = unfavourable.min(opening.csm());
            Amount excess = unfavourable.subtract(csmReduction);
            csmMovement = csmReduction.negate();
            lcMovement = excess;

            entries.add(journal.entry("future-service.fcf", List.of(
                    journal.debit(Accounts.PNL_INSURANCE_SERVICE, unfavourable),
                    journal.credit(Accounts.FCF_LIABILITY, unfavourable))));
            if (csmReduction.isPositive()) {
                entries.add(journal.entry("future-service.csm-release", List.of(
                        journal.debit(Accounts.CSM_LIABILITY, csmReduction),
                        journal.credit(Accounts.PNL_INSURANCE_SERVICE,
                                csmReduction))));
            }
            if (excess.isPositive()) {
                entries.add(journal.entry("future-service.loss-add", List.of(
                        journal.debit(Accounts.LOSS_COMPONENT_CONTRA, excess),
                        journal.credit(Accounts.LOSS_COMPONENT_MEMO, excess))));
            }
        } else if (change.isPositive()) {
            Amount lcRecovery = change.min(opening.lossComponent());
            Amount csmRecovery = change.subtract(lcRecovery);
            csmMovement = csmRecovery;
            lcMovement = lcRecovery.negate();

            entries.add(journal.entry("future-service.fcf", List.of(
                    journal.debit(Accounts.FCF_LIABILITY, change),
                    journal.credit(Accounts.PNL_INSURANCE_SERVICE, change))));
            if (lcRecovery.isPositive()) {
                entries.add(journal.entry(
                        "future-service.loss-recover", List.of(
                                journal.debit(Accounts.LOSS_COMPONENT_MEMO,
                                        lcRecovery),
                                journal.credit(Accounts.LOSS_COMPONENT_CONTRA,
                                        lcRecovery))));
            }
            if (csmRecovery.isPositive()) {
                entries.add(journal.entry("future-service.csm-recover",
                        List.of(
                                journal.debit(Accounts.PNL_INSURANCE_SERVICE,
                                        csmRecovery),
                                journal.credit(Accounts.CSM_LIABILITY,
                                        csmRecovery))));
            }
        } else {
            csmMovement = Amount.zero();
            lcMovement = Amount.zero();
        }

        return new StepResult(
                StepType.FUTURE_SERVICE_CHANGE,
                BalanceTriple.roll(opening.fulfillmentCashFlow(), fcfMovement),
                BalanceTriple.roll(opening.csm(), csmMovement),
                BalanceTriple.roll(opening.lossComponent(), lcMovement),
                List.copyOf(entries));
    }
}
