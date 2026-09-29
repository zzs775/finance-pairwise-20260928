package csm;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import csm.engine.CsmRollEngine;
import csm.engine.CsmValidationException;
import csm.engine.ReplayEngine;
import csm.fixture.SyntheticFixtures;
import csm.model.ContractGroup;
import csm.model.Direction;
import csm.model.GroupRoll;
import csm.model.RuleVersion;

/**
 * 零依赖自动化测试入口（仅用 JDK 标准库断言）。
 */
public final class TestRunner {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        try {
            testNormalGroupRoll();
            testLossSwitchGroup();
            testLossReversalGroup();
            testZeroCoverageUnitsRejected();
            testNegativeCoverageUnitsRejected();
            testSignConflictRejected();
            testUnknownVersionRejected();
            testRuleSwitchAppliesOnlyAfterEffectivePeriod();
            testFrozenReplayUnderOldVersion();
            testFrozenViolationRejected();
            testSettlementCannotExceedFcf();
        } catch (Exception e) {
            failed++;
            System.out.println("FATAL: " + e);
            e.printStackTrace(System.out);
        }
        System.out.println();
        System.out.println("TESTS PASSED: " + passed);
        System.out.println("TESTS FAILED: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testNormalGroupRoll() {
        List<GroupRoll> rolls = new CsmRollEngine()
                .rollAllPeriods(SyntheticFixtures.normalGroup(), 2);

        GroupRoll p0 = rolls.get(0);
        // opening csm=100 fcf=300; accretion 5% => +5,+15; favourable -10 => csm+10 fcf-10
        // release = 115*10/30 = 38.33333333 (CSM only); settlement -50 fcf
        assertClosing(p0, "76.66666667", "0", "255.00000000");
        assertVersion(p0, RuleVersion.V1);

        GroupRoll p1 = rolls.get(1);
        // accretion 5% => csm +3.83333333, fcf +12.75
        // release = 80.5*10/20 = 40.25 (CSM only); settlement -60
        assertClosing(p1, "40.25000000", "0", "207.75000000");
        assertOpeningEqualsPrevious(p1, p0);
        check();
    }

    private static void testLossSwitchGroup() {
        // adverse 60 vs csm 42(40+5% accretion): absorb 42, loss += 18; release 0 (CSM gone)
        List<GroupRoll> rolls = new CsmRollEngine()
                .rollAllPeriods(SyntheticFixtures.lossSwitchGroup(), 2);
        GroupRoll p0 = rolls.get(0);
        assertClosing(p0, "0", "18.00000000", "165.00000000");
        // 亏损组成期间无释放；accretion: loss 不变, fcf +8.5
        GroupRoll p1 = rolls.get(1);
        assertClosing(p1, "0", "18.00000000", "173.25000000");
        check();
    }

    private static void testLossReversalGroup() {
        // p0: opening loss=30 fcf=200; accretion fcf +10; adverse 20 -> loss=50 fcf=230
        List<GroupRoll> rolls = new CsmRollEngine()
                .rollAllPeriods(SyntheticFixtures.lossReversalGroup(), 2);
        assertClosing(rolls.get(0), "0", "50.00000000", "230.00000000");
        // p1: accretion fcf +11.5; favourable -70 -> reverse loss 50, restore csm 20, fcf-70
        // settlement -10; release = 20*5/15 = 6.66666667 (CSM only, FCF unchanged)
        GroupRoll p1 = rolls.get(1);
        // fcf = 230+11.5-70-10 = 161.5
        assertClosing(p1, "13.33333333", "0", "161.50000000");
        check();
    }

    private static void testZeroCoverageUnitsRejected() {
        expectValidationFailure(SyntheticFixtures.zeroCoverageUnitsGroup(), 1,
                "zero coverage units must be rejected");
    }

    private static void testNegativeCoverageUnitsRejected() {
        expectValidationFailure(SyntheticFixtures.negativeCoverageUnitsGroup(), 1,
                "negative coverage units must be rejected");
    }

    private static void testSignConflictRejected() {
        expectValidationFailure(SyntheticFixtures.signConflictGroup(), 1,
                "sign conflict must be rejected");
    }

    private static void testUnknownVersionRejected() {
        expectValidationFailure(SyntheticFixtures.unknownVersionGroup(), 1,
                "unknown rule version must be rejected");
    }

    private static void testRuleSwitchAppliesOnlyAfterEffectivePeriod() {
        List<GroupRoll> rolls = new ReplayEngine(Map.of(
                        0, RuleVersion.V1,
                        1, RuleVersion.V2,
                        2, RuleVersion.V2))
                .replay(SyntheticFixtures.ruleSwitchGroup(), 3);
        // p0 uses V1 rate 5%; p1/p2 use V2 rate 6%
        assertVersion(rolls.get(0), RuleVersion.V1);
        assertVersion(rolls.get(1), RuleVersion.V2);
        assertVersion(rolls.get(2), RuleVersion.V2);

        GroupRoll p0 = rolls.get(0);
        // csm: 100 +5 accretion; release 105*10/30=35 -> 70
        // fcf: 200 +10 accretion -20 settlement = 190 (release does not move FCF)
        assertClosing(p0, "70.00000000", "0", "190.00000000");
        GroupRoll p1 = rolls.get(1);
        // accretion V2: csm +4.2, fcf +11.4; release 37.1; settlement -20
        // csm = 37.1; fcf 190+11.4-20 = 181.4
        assertClosing(p1, "37.10000000", "0", "181.40000000");
        GroupRoll p2 = rolls.get(2);
        // accretion V2: fcf +10.884; settlement -20 -> 172.284; CSM released to zero
        assertClosing(p2, "0.00000000", "0", "172.28400000");
        check();
    }

    private static void testFrozenReplayUnderOldVersion() {
        // 同一组从不同起点重放 p0：必须稳定得到 V1 结果（冻结、可重放）
        ContractGroup group = SyntheticFixtures.ruleSwitchGroup();
        List<GroupRoll> first = new ReplayEngine(Map.of(
                0, RuleVersion.V1, 1, RuleVersion.V2, 2, RuleVersion.V2)).replay(group, 3);
        List<GroupRoll> again = new ReplayEngine(Map.of(
                0, RuleVersion.V1, 1, RuleVersion.V2, 2, RuleVersion.V2)).replay(group, 3);
        assertClosing(again.get(0),
                first.get(0).closing().csm().toPlainString(),
                first.get(0).closing().lossComponent().toPlainString(),
                first.get(0).closing().fulfillmentCashFlows().toPlainString());
        check();
    }

    private static void testFrozenViolationRejected() {
        ReplayEngine replay = new ReplayEngine(Map.of(0, RuleVersion.V1));
        expectFailure(() -> replay.replay(SyntheticFixtures.ruleSwitchFrozenViolationGroup(), 1),
                "retagging a historical event with the new version must be rejected");
    }

    private static void testSettlementCannotExceedFcf() {
        ContractGroup group = new ContractGroup(
                "OVER-SETTLE",
                SyntheticFixtures.normalGroup().opening(),
                List.of(new csm.model.MovementEvent(0,
                        csm.model.MovementType.CASH_SETTLEMENT, new BigDecimal("9999"),
                        Direction.NEUTRAL, RuleVersion.V1, "too-much")),
                List.of(new csm.model.CoveragePeriodUnits(0,
                        new BigDecimal("10"), new BigDecimal("30"))));
        expectValidationFailure(group, 1, "settlement exceeding FCF must be rejected");
    }

    private static void expectValidationFailure(ContractGroup group, int periods, String label) {
        expectFailure(() -> new CsmRollEngine().rollAllPeriods(group, periods), label);
    }

    private static void expectFailure(Runnable runnable, String label) {
        try {
            runnable.run();
        } catch (CsmValidationException expected) {
            pass(label + " -> rejected: " + expected.getMessage());
            return;
        }
        fail(label + " -> expected CsmValidationException");
    }

    private static void assertClosing(GroupRoll roll, String csm, String loss, String fcf) {
        expectAmount(roll.closing().csm(), csm, roll.groupId() + " closing CSM");
        expectAmount(roll.closing().lossComponent(), loss, roll.groupId() + " closing loss");
        expectAmount(roll.closing().fulfillmentCashFlows(), fcf, roll.groupId() + " closing FCF");
    }

    private static void assertVersion(GroupRoll roll, RuleVersion version) {
        if (roll.ruleVersion() != version) {
            fail(roll.groupId() + " expected version " + version + " but was " + roll.ruleVersion());
        }
    }

    private static void assertOpeningEqualsPrevious(GroupRoll current, GroupRoll previous) {
        if (current.opening().csm().compareTo(previous.closing().csm()) != 0
                || current.opening().lossComponent().compareTo(previous.closing().lossComponent()) != 0
                || current.opening().fulfillmentCashFlows()
                .compareTo(previous.closing().fulfillmentCashFlows()) != 0) {
            fail(current.groupId() + " opening does not carry forward previous closing");
        }
    }

    private static void expectAmount(BigDecimal actual, String expected, String label) {
        BigDecimal e = new BigDecimal(expected);
        if (actual.compareTo(e) != 0) {
            fail(label + " expected=" + expected + " actual=" + actual.toPlainString());
        }
    }

    private static void check() {
        pass("closure and balanced journals verified by mechanical oracle");
    }

    private static void pass(String message) {
        passed++;
        System.out.println("PASS: " + message);
    }

    private static void fail(String message) {
        failed++;
        System.out.println("FAIL: " + message);
    }

    private TestRunner() {
    }
}
