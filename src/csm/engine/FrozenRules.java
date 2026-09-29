package csm.engine;

import csm.model.Amount;
import csm.model.PeriodInput;
import csm.model.RuleVersion;

import java.math.BigDecimal;

/** 规则注册表：版本差异只对生效后的事件起作用；历史事件按戳记版本重放。 */
public final class FrozenRules {

    public RuleVersion resolve(String code) {
        return RuleVersion.fromCode(code);
    }

    /** V1 使用锁定利率展开 CSM；V2 使用当期利率展开 CSM。 */
    public Amount csmAccretionRate(RuleVersion version, PeriodInput input) {
        return switch (version) {
            case RULES_V1 -> input.lockedRate();
            case RULES_V2 -> input.currentRate();
        };
    }

    public Amount fcfAccretionRate(PeriodInput input) {
        return input.currentRate();
    }

    public BigDecimal monetaryScale() {
        return BigDecimal.valueOf(Amount.SCALE);
    }
}
