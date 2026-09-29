package csm.model;

/** 单条分录行：借方/贷方金额恰一侧非零。负债增加记贷方。 */
public record Posting(String account, Amount debit, Amount credit) {
}
