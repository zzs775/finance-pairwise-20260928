package csm;

import csm.engine.CsmEngine;
import csm.engine.MechanicalOracle;
import csm.engine.ReplayEngine;
import csm.fixture.SyntheticFixtures;
import csm.model.Amount;
import csm.model.GroupRollforward;
import csm.model.RuleVersion;
import csm.model.StepResult;
import csm.model.StepType;

import java.util.List;

import static csm.TestSupport.assertEquals;
import static csm.TestSupport.assertTrue;

/** 规则换版与重放：换版只影响生效后事件，历史期间按原版本确定性重放。 */
public final class RuleChangeReplayTest {

    public void run() {
        var fixtures = new SyntheticFixtures();
        var periods = fixtures.ruleChangeReplayPeriods();
        var replay = new ReplayEngine();

        List<GroupRollforward> firstRun = replay.replay(periods);
        List<GroupRollforward> secondRun = replay.replay(periods);

        assertEquals(2, firstRun.size(), "two periods replayed");

        GroupRollforward historical = firstRun.get(0);
        GroupRollforward changed = firstRun.get(1);

        assertEquals(RuleVersion.RULES_V1, historical.appliedVersion(),
                "historical period keeps V1");
        assertEquals(RuleVersion.RULES_V2, changed.appliedVersion(),
                "later period applies V2");

        StepResult historicalAccretion = historical.steps().get(0);
        StepResult changedAccretion = changed.steps().get(0);
        assertEquals(StepType.INTEREST_ACCRETION,
                historicalAccretion.type(), "step ordering");
        assertEquals(Amount.of("2.00"),
                historicalAccretion.csm().movement(),
                "V1 accretes CSM at locked rate 2%");
        assertEquals(Amount.of("6.12"),
                changedAccretion.csm().movement(),
                "V2 accretes CSM at current rate 8%");

        assertTrue(!historicalAccretion.csm().movement()
                        .equals(changedAccretion.csm().movement()),
                "rule change alters only post-effective events");

        for (int index = 0; index < firstRun.size(); index++) {
            assertEquals(firstRun.get(index).closingFcf(),
                    secondRun.get(index).closingFcf(),
                    "deterministic replay fcf " + index);
            assertEquals(firstRun.get(index).closingCsm(),
                    secondRun.get(index).closingCsm(),
                    "deterministic replay csm " + index);
            assertEquals(firstRun.get(index).closingLossComponent(),
                    secondRun.get(index).closingLossComponent(),
                    "deterministic replay lc " + index);
            assertEquals(firstRun.get(index).appliedVersion(),
                    secondRun.get(index).appliedVersion(),
                    "replayed period keeps frozen version " + index);
        }

        var oracle = new MechanicalOracle();
        for (GroupRollforward rollforward : secondRun) {
            oracle.verify(rollforward);
        }
    }
}
