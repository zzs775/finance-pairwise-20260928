package loanengine;

public final class AllTests {

    public static void main(String[] args) {
        DatesTest.run();
        AmortizationTest.run();
        ScheduleTest.run();
        FloatingRateTest.run();
        PrepaymentTest.run();
        ValidationTest.run();
        TestFramework.summary();
    }
}
