package csm;

import csm.engine.CsmEngine;
import csm.engine.MechanicalOracle;
import csm.fixture.SyntheticFixtures;
import csm.model.Amount;
import csm.model.GroupRollforward;
import csm.model.StepResult;
import csm.model.StepType;

import static csm.TestSupport.assertEquals;
import static csm.TestSupport.assertTrue;

/** 亏损切换：不利未来服务变动超过 CSM，超额转入亏损组成且 CSM 保持非负。 */
public final class LossSwitchTest {

    public void run() {
        var engine = new CsmEngine();
        var oracle = new MechanicalOracle();
        var fixtures = new SyntheticFixtures();

        GroupRollforward result = engine.run(fixtures.lossTriggerPeriod());
        oracle.verify(result);

        StepResult futureService = result.steps().get(1);
        assertEquals(StepType.FUTURE_SERVICE_CHANGE, futureService.type(),
                "future service step");
        assertEquals(Amount.zero(), futureService.csm().closing(),
                "csm floored at zero");
        assertEquals(Amount.of("58.80"),
                futureService.lossComponent().closing(),
                "excess adverse change enters loss component");
        assertEquals(Amount.of("-41.20"),
                futureService.csm().movement(),
                "csm fully consumed by adverse change");
        assertEquals(Amount.of("58.80"),
                futureService.lossComponent().movement(),
                "loss component added");

        assertEquals(Amount.zero(), result.closingCsm(),
                "closing csm zero");
        assertEquals(Amount.of("44.10"), result.closingLossComponent(),
                "closing loss component after release");
        assertTrue(result.closingLossComponent().isPositive(),
                "group is loss-making");

        for (StepResult step : result.steps()) {
            assertTrue(step.csm().closing().isNonNegative(),
                    "csm never negative at " + step.type());
        }
    }
}
