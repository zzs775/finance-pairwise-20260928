package csm.fixture;

import csm.model.ContractGroup;

import java.math.BigDecimal;
import java.util.List;

import csm.model.Balances;
import csm.model.CoveragePeriodUnits;
import csm.model.Direction;
import csm.model.MovementEvent;
import csm.model.MovementType;
import csm.model.RuleVersion;

/**
 * 合成 fixture 工厂：正常组、亏损切换组、亏损转回组、零覆盖单位组、规则换版组。
 */
public final class SyntheticFixtures {

    private SyntheticFixtures() {
    }

    private static BigDecimal v(String value) {
        return new BigDecimal(value);
    }

    private static Balances balances(String csm, String loss, String fcf) {
        return new Balances(v(csm), v(loss), v(fcf));
    }

    private static MovementEvent event(int period, MovementType type, String amount,
                                       Direction direction, RuleVersion version, String description) {
        return new MovementEvent(period, type, v(amount), direction, version, description);
    }

    private static CoveragePeriodUnits units(int period, String current, String total) {
        return new CoveragePeriodUnits(period, v(current), v(total));
    }

    public static ContractGroup normalGroup() {
        return new ContractGroup(
                "NORMAL",
                balances("100", "0", "300"),
                List.of(
                        event(0, MovementType.FUTURE_SERVICE_CHANGE, "-10",
                                Direction.FAVORABLE, RuleVersion.V1, "fav-exp-update"),
                        event(0, MovementType.CASH_SETTLEMENT, "50",
                                Direction.NEUTRAL, RuleVersion.V1, "claim-paid"),
                        event(1, MovementType.CASH_SETTLEMENT, "60",
                                Direction.NEUTRAL, RuleVersion.V1, "claim-paid")),
                List.of(
                        units(0, "10", "30"),
                        units(1, "10", "20")));
    }

    public static ContractGroup lossSwitchGroup() {
        return new ContractGroup(
                "LOSS-SWITCH",
                balances("40", "0", "100"),
                List.of(
                        event(0, MovementType.FUTURE_SERVICE_CHANGE, "60",
                                Direction.ADVERSE, RuleVersion.V1, "adverse-exceeds-csm")),
                List.of(
                        units(0, "10", "20"),
                        units(1, "10", "10")));
    }

    public static ContractGroup lossReversalGroup() {
        return new ContractGroup(
                "LOSS-REVERSAL",
                balances("0", "30", "200"),
                List.of(
                        event(0, MovementType.FUTURE_SERVICE_CHANGE, "20",
                                Direction.ADVERSE, RuleVersion.V1, "further-adverse"),
                        event(1, MovementType.FUTURE_SERVICE_CHANGE, "-70",
                                Direction.FAVORABLE, RuleVersion.V1, "fav-reverses-loss"),
                        event(1, MovementType.CASH_SETTLEMENT, "10",
                                Direction.NEUTRAL, RuleVersion.V1, "claim-paid")),
                List.of(
                        units(0, "5", "20"),
                        units(1, "5", "15")));
    }

    public static ContractGroup zeroCoverageUnitsGroup() {
        return new ContractGroup(
                "ZERO-UNITS",
                balances("50", "0", "100"),
                List.of(),
                List.of(units(0, "0", "20")));
    }

    /** 覆盖单位为负数，同样必须分类失败。 */
    public static ContractGroup negativeCoverageUnitsGroup() {
        return new ContractGroup(
                "NEGATIVE-UNITS",
                balances("50", "0", "100"),
                List.of(),
                List.of(units(0, "-1", "20")));
    }

    /** 方向标记与金额符号冲突，必须拒绝。 */
    public static ContractGroup signConflictGroup() {
        return new ContractGroup(
                "SIGN-CONFLICT",
                balances("50", "0", "100"),
                List.of(
                        event(0, MovementType.FUTURE_SERVICE_CHANGE, "20",
                                Direction.FAVORABLE, RuleVersion.V1, "claimed-fav-but-positive")),
                List.of(units(0, "10", "20")));
    }

    /** 未知（null）规则版本，必须拒绝。 */
    public static ContractGroup unknownVersionGroup() {
        return new ContractGroup(
                "UNKNOWN-VERSION",
                balances("50", "0", "100"),
                List.of(
                        new MovementEvent(0, MovementType.CASH_SETTLEMENT, v("5"),
                                Direction.NEUTRAL, null, "no-version")),
                List.of(units(0, "10", "20")));
    }

    public static ContractGroup ruleSwitchGroup() {
        return new ContractGroup(
                "RULE-SWITCH",
                balances("100", "0", "200"),
                List.of(
                        event(0, MovementType.CASH_SETTLEMENT, "20",
                                Direction.NEUTRAL, RuleVersion.V1, "p0-old-rule"),
                        event(1, MovementType.CASH_SETTLEMENT, "20",
                                Direction.NEUTRAL, RuleVersion.V2, "p1-new-rule"),
                        event(2, MovementType.CASH_SETTLEMENT, "20",
                                Direction.NEUTRAL, RuleVersion.V2, "p2-new-rule")),
                List.of(
                        units(0, "10", "30"),
                        units(1, "10", "20"),
                        units(2, "10", "10")));
    }

    /** 规则在第1期换版，但历史事件错误地标成新版本，重放必须拒绝。 */
    public static ContractGroup ruleSwitchFrozenViolationGroup() {
        return new ContractGroup(
                "RULE-SWITCH-VIOLATION",
                balances("100", "0", "200"),
                List.of(
                        event(0, MovementType.CASH_SETTLEMENT, "20",
                                Direction.NEUTRAL, RuleVersion.V2, "p0-wrongly-retagged")),
                List.of(units(0, "10", "30")));
    }
}
