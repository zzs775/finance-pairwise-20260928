package csm.model;

import java.util.List;

/** 一个组一个期间的完整滚动结果，四步明细 + 期末余额。 */
public record GroupRollforward(
        String groupId,
        String periodId,
        RuleVersion appliedVersion,
        PeriodInput input,
        List<StepResult> steps) {

    public Amount closingFcf() {
        return steps.getLast().fulfillmentCashFlow().closing();
    }

    public Amount closingCsm() {
        return steps.getLast().csm().closing();
    }

    public Amount closingLossComponent() {
        return steps.getLast().lossComponent().closing();
    }

    public List<JournalEntry> allEntries() {
        return steps.stream()
                .flatMap(step -> step.entries().stream())
                .toList();
    }
}
