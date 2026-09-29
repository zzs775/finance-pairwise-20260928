package csm.fixture;

import csm.model.Amount;
import csm.model.OpeningBalances;
import csm.model.PeriodInput;

import java.util.List;

/** 合成测试夹具：正常组、亏损切换、亏损转回、零覆盖单位、规则换版/重放。 */
public final class SyntheticFixtures {

    public static OpeningBalances opening(String fcf, String csm,
                                          String lossComponent) {
        return new OpeningBalances(Amount.of(fcf), Amount.of(csm),
                Amount.of(lossComponent));
    }

    public static PeriodInput period(String group, String period, int sequence,
                                     String version,
                                     OpeningBalances opening,
                                     String currentRate, String lockedRate,
                                     String futureServiceChange,
                                     String currentUnits,
                                     String remainingUnits,
                                     String settlement) {
        return new PeriodInput(group, period, sequence, version, opening,
                Amount.of(currentRate), Amount.of(lockedRate),
                Amount.of(futureServiceChange),
                Amount.of(currentUnits), Amount.of(remainingUnits),
                Amount.of(settlement));
    }

    public PeriodInput normalGroupPeriod() {
        return period("G-NORMAL", "P1", 0, "V1",
                opening("100.00", "80.00", "0.00"),
                "0.05", "0.03", "20.00",
                "10", "40", "30.00");
    }

    public PeriodInput lossTriggerPeriod() {
        return period("G-LOSS", "P1", 0, "V1",
                opening("100.00", "40.00", "0.00"),
                "0.05", "0.03", "-100.00",
                "10", "40", "10.00");
    }

    public PeriodInput lossRecoveryPeriod() {
        return period("G-LOSS", "P2", 1, "V1",
                opening("150.30", "0.00", "46.30"),
                "0.05", "0.03", "60.00",
                "10", "30", "10.00");
    }

    public PeriodInput zeroCoveragePeriod() {
        return period("G-ZERO", "P1", 0, "V1",
                opening("100.00", "80.00", "0.00"),
                "0.05", "0.03", "0.00",
                "0", "40", "0.00");
    }

    public PeriodInput negativeCoveragePeriod() {
        return period("G-NEG", "P1", 0, "V1",
                opening("100.00", "80.00", "0.00"),
                "0.05", "0.03", "0.00",
                "-5", "40", "0.00");
    }

    public PeriodInput unknownVersionPeriod() {
        return period("G-BAD-VERSION", "P1", 0, "V9",
                opening("100.00", "80.00", "0.00"),
                "0.05", "0.03", "0.00",
                "10", "40", "0.00");
    }

    public PeriodInput signConflictPeriod() {
        return period("G-BAD-SIGN", "P1", 0, "V1",
                opening("100.00", "-80.00", "0.00"),
                "0.05", "0.03", "0.00",
                "10", "40", "0.00");
    }

    public List<PeriodInput> ruleChangeReplayPeriods() {
        return List.of(
                period("G-VERSION", "P1", 0, "V1",
                        opening("100.00", "100.00", "0.00"),
                        "0.08", "0.02", "0.00",
                        "10", "40", "0.00"),
                period("G-VERSION", "P2", 1, "V2",
                        opening("0.00", "0.00", "0.00"),
                        "0.08", "0.02", "0.00",
                        "10", "30", "0.00"));
    }
}
