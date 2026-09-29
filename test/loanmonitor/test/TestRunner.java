package loanmonitor.test;

/** Runs all zero-dependency tests and exits non-zero on any failure. */
public final class TestRunner {

    public static void main(String[] args) {
        TestHarness h = new TestHarness();
        MetricBoundaryTest.run(h);
        RollingWindowTest.run(h);
        MissingFactTest.run(h);
        GraceAndWaiverTest.run(h);
        RestatementTest.run(h);
        OverflowTest.run(h);
        CanonicalOrderTest.run(h);

        System.out.println("SUMMARY passed=" + h.passed()
                + " failed=" + h.failed());
        if (h.failed() > 0) {
            System.exit(1);
        }
    }
}
