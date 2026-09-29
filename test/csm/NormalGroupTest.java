package csm;

import csm.engine.CsmEngine;
import csm.engine.MechanicalOracle;
import csm.fixture.SyntheticFixtures;
import csm.model.Amount;
import csm.model.GroupRollforward;
import csm.model.StepType;

import static csm.TestSupport.assertEquals;
import static csm.TestSupport.assertTrue;

/** 正常组：四步滚动、期末 CSM/亏损组成闭合。 */
public final class NormalGroupTest {

    public void run() {
        var engine = new CsmEngine();
        var oracle = new MechanicalOracle();
        var fixtures = new SyntheticFixtures();

        GroupRollforward result = engine.run(fixtures.normalGroupPeriod());
        oracle.verify(result);

        assertEquals(4, result.steps().size(), "four ordered steps");
        assertEquals(StepType.INTEREST_ACCRETION,
                result.steps().get(0).type(), "step 1");
        assertEquals(StepType.FUTURE_SERVICE_CHANGE,
                result.steps().get(1).type(), "step 2");
        assertEquals(StepType.SERVICE_RELEASE,
                result.steps().get(2).type(), "step 3");
        assertEquals(StepType.SETTLEMENT,
                result.steps().get(3).type(), "step 4");

        assertEquals(Amount.of("55.00"), result.closingFcf(),
                "closing fcf");
        assertEquals(Amount.of("76.80"), result.closingCsm(),
                "closing csm");
        assertEquals(Amount.zero(), result.closingLossComponent(),
                "closing loss component");

        assertTrue(result.closingCsm().isNonNegative(),
                "csm non-negative");
        assertTrue(result.closingLossComponent().isNonNegative(),
                "loss component non-negative");
        assertTrue(!result.allEntries().isEmpty(),
                "business movements produce entries");
    }
}
