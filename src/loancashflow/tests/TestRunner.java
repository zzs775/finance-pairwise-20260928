package loancashflow.tests;

/** Entry point for the automated test suite. */
public final class TestRunner {

    private TestRunner() {
    }

    public static void main(String[] args) {
        TestFramework framework = new TestFramework();
        new ScheduleEngineTests(framework).run();
        new ManualOracleTests(framework).run();
        new ValidationTests(framework).run();
        framework.report();
    }
}
