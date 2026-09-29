package csm;

import csm.engine.CsmEngine;
import csm.fixture.SyntheticFixtures;
import csm.model.ClassificationException;
import csm.model.ValidationException;

import static csm.TestSupport.assertThrows;

/** 零/负覆盖单位：分类失败并被拒绝。 */
public final class ZeroCoverageTest {

    public void run() {
        var engine = new CsmEngine();
        var fixtures = new SyntheticFixtures();

        assertThrows(ClassificationException.class,
                () -> engine.run(fixtures.zeroCoveragePeriod()),
                "zero coverage units");
        assertThrows(ClassificationException.class,
                () -> engine.run(fixtures.negativeCoveragePeriod()),
                "negative coverage units");

        assertThrows(ValidationException.class,
                () -> engine.run(fixtures.unknownVersionPeriod()),
                "unknown rule version must be rejected");
        assertThrows(ValidationException.class,
                () -> engine.run(fixtures.signConflictPeriod()),
                "sign conflict must be rejected");
    }
}
