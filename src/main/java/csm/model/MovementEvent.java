package csm.model;

import java.math.BigDecimal;

/**
 * 单个业务变动事件。事件自带规则版本，保证历史期间可按原版本冻结重放。
 * 金额符号约定见 README 符号表。
 */
public record MovementEvent(
        int periodIndex,
        MovementType type,
        BigDecimal amount,
        Direction direction,
        RuleVersion ruleVersion,
        String description) {
}
