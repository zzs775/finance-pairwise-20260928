package csm.model;

import java.util.List;

/**
 * 单个合同组单期间的完整滚动结果。
 */
public record GroupRoll(
        String groupId,
        int periodIndex,
        RuleVersion ruleVersion,
        Balances opening,
        List<StepRoll> steps,
        Balances closing,
        List<JournalEntry> entries) {
}
