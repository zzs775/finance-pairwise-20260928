package fi;

public final class FrequencyTest {

    private FrequencyTest() {
    }

    static void run(Tests.Asserts a) {
        a.equal("quarterly months", Frequency.QUARTERLY.months(), 3);
        a.equal("semi periods/year", Frequency.SEMI_ANNUAL.periodsPerYear(), 2);
        a.equal("annual periods/year", Frequency.ANNUALLY.periodsPerYear(), 1);
        a.equal("from 6", Frequency.fromMonths(6), Frequency.SEMI_ANNUAL);
        a.throwsCode("from 4 illegal", ErrorCode.ILLEGAL_FREQUENCY,
                () -> Frequency.fromMonths(4));
        a.throwsCode("from 0 illegal", ErrorCode.ILLEGAL_FREQUENCY,
                () -> Frequency.fromMonths(0));
    }
}
