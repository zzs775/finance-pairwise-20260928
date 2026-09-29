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

/** 亏损转回：后续有利变动先转回亏损组成，再恢复 CSM。 */
public final class LossRecoveryTest {

    public void run() {
        var engine = new CsmEngine();
        var oracle = new MechanicalOracle();
        var fixtures = new SyntheticFixtures();

        GroupRollforward result = engine.run(fixtures.lossRecoveryPeriod());
        oracle.verify(result);

        StepResult futureService = result.steps().get(1);
        assertEquals(StepType.FUTURE_SERVICE_CHANGE, futureService.type(),
                "future service step");
        assertEquals(Amount.zero(), futureService.lossComponent().closing(),
                "loss component reversed first");
        assertEquals(Amount.of("13.70"), futureService.csm().closing(),
                "residual favourable change restores CSM");
        assertEquals(Amount.of("-46.30"),
                futureService.lossComponent().movement(),
                "full existing loss component reversed before CSM recovery");
        assertEquals(Amount.of("13.70"), futureService.csm().movement(),
                "only excess beyond LC reaches CSM");

        assertTrue(result.closingCsm().isPositive(),
                "CSM restored after loss reversal");
        assertEquals(Amount.zero(), result.closingLossComponent(),
                "no loss component remains");
    }
}
