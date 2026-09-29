package csm.model;

/**
 * 单期单组输入。
 * futureServiceChange 符号：正数=有利未来服务变动，负数=不利未来服务变动。
 * settlement 为当期现金结算流出的非负金额。
 */
public record PeriodInput(
        String groupId,
        String periodId,
        int sequence,
        String ruleVersionCode,
        OpeningBalances opening,
        Amount currentRate,
        Amount lockedRate,
        Amount futureServiceChange,
        Amount currentCoverageUnits,
        Amount remainingCoverageUnits,
        Amount settlement) {
}
