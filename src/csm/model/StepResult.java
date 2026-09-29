package csm.model;

import java.util.List;

/** 单个步骤结果：FCF/CSM/亏损组成各自保留期初、变动、期末，并附本步分录。 */
public record StepResult(
        StepType type,
        BalanceTriple fulfillmentCashFlow,
        BalanceTriple csm,
        BalanceTriple lossComponent,
        List<JournalEntry> entries) {
}
