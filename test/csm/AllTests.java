package csm;

/** 测试聚合入口，依次执行各场景用例，全部通过退出码 0。 */
public final class AllTests {

    private record Case(String name, Runnable body) {
    }

    public static void main(String[] args) {
        var cases = new Case[] {
                new Case("NormalGroup", new NormalGroupTest()::run),
                new Case("LossSwitch", new LossSwitchTest()::run),
                new Case("LossRecovery", new LossRecoveryTest()::run),
                new Case("ZeroCoverage", new ZeroCoverageTest()::run),
                new Case("RuleChangeReplay",
                        new RuleChangeReplayTest()::run)
        };

        int failures = 0;
        for (Case testCase : cases) {
            try {
                testCase.body.run();
                System.out.println("PASS " + testCase.name());
            } catch (Throwable thrown) {
                failures++;
                System.out.println("FAIL " + testCase.name() + ": "
                        + thrown.getMessage());
                thrown.printStackTrace(System.out);
            }
        }

        if (failures > 0) {
            System.out.println(failures + " TEST(S) FAILED");
            System.exit(1);
        }
        System.out.println("ALL " + cases.length + " TESTS PASSED");
    }
}
