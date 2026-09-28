package fix.recovery.test;

/** Test entry point; exits non-zero on any failure. */
public final class TestRunner {
    public static void main(String[] args) {
        TestFramework t = new TestFramework();
        ParserTest.register(t);
        SequenceTest.register(t);
        ResendTest.register(t);
        CommitTest.register(t);
        OracleTest.register(t);
        CliTest.register(t);
        System.exit(t.report());
    }
}
