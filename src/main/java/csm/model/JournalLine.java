package csm.model;

import java.math.BigDecimal;

/**
 * 单条分录行：debit 与 credit 中恰有一个为正、另一个为零。
 */
public record JournalLine(
        Account account,
        BigDecimal debit,
        BigDecimal credit) {
}
