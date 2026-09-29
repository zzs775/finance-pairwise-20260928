package csm.model;

import java.util.List;

/**
 * 单个处理步骤的三态滚动：期初、变动、期末，以及该步生成的分录。
 */
public record StepRoll(
        String stepName,
        Balances opening,
        Balances movement,
        Balances closing,
        List<JournalEntry> entries) {
}
