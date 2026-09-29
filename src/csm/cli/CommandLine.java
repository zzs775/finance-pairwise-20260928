package csm.cli;

import csm.engine.CsmEngine;
import csm.engine.MechanicalOracle;
import csm.engine.ReplayEngine;
import csm.fixture.SyntheticFixtures;
import csm.model.GroupRollforward;

import java.util.List;

/** 命令行入口：运行合成 fixture，打印每组各步滚动与 oracle 校验结论。 */
public final class CommandLine {

    public static void main(String[] args) {
        var fixtures = new SyntheticFixtures();
        var engine = new CsmEngine();
        var oracle = new MechanicalOracle();

        runOne(engine, oracle, fixtures.normalGroupPeriod());
        runOne(engine, oracle, fixtures.lossTriggerPeriod());
        runOne(engine, oracle, fixtures.lossRecoveryPeriod());

        System.out.println("== RULE CHANGE + HISTORICAL REPLAY ==");
        List<GroupRollforward> replay = new ReplayEngine(engine, oracle)
                .replay(fixtures.ruleChangeReplayPeriods());
        for (GroupRollforward rollforward : replay) {
            ReportWriter.write(System.out, rollforward);
        }

        reportRejected(fixtures.zeroCoveragePeriod(), engine, "zero coverage");
        reportRejected(fixtures.negativeCoveragePeriod(), engine,
                "negative coverage");
        reportRejected(fixtures.unknownVersionPeriod(), engine,
                "unknown rule version");
        reportRejected(fixtures.signConflictPeriod(), engine,
                "sign conflict");

        System.out.println("ALL FIXTURES PROCESSED, ORACLE VERIFIED");
    }

    private static void runOne(CsmEngine engine, MechanicalOracle oracle,
                               csm.model.PeriodInput input) {
        GroupRollforward result = engine.run(input);
        oracle.verify(result);
        ReportWriter.write(System.out, result);
    }

    private static void reportRejected(csm.model.PeriodInput input,
                                       CsmEngine engine, String label) {
        try {
            engine.run(input);
            System.out.printf("REJECT SCENARIO %-18s UNEXPECTED ACCEPTANCE%n",
                    label);
        } catch (csm.model.ValidationException ex) {
            System.out.printf("REJECT SCENARIO %-18s REJECTED: %s%n%n",
                    label, ex.getMessage());
        }
    }
}
