package fi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ValidationTest {

    private ValidationTest() {
    }

    static void run(Tests.Asserts a) {
        BondTerms good = SampleBonds.regular();
        TermsValidator.validate(good);
        a.that("valid sample passes", true);

        a.throwsCode("maturity before value direct", ErrorCode.DATE_ORDER,
                () -> TermsValidator.validate(new BondTerms(
                        "REV", LocalDate.of(2026, 1, 1), LocalDate.of(2025, 1, 1),
                        good.frequency(), good.stubType(), good.dayCount(),
                        good.eom(), good.convention(), good.exDividendBusinessDays(),
                        good.rateSteps(), good.notionalSteps(), good.calendar())));
        a.throwsCode("equal dates", ErrorCode.DATE_ORDER,
                () -> TermsValidator.validate(copy(good,
                        good.valueDate(), good.valueDate())));

        a.throwsCode("negative notional", ErrorCode.NEGATIVE_NOTIONAL,
                () -> TermsValidator.validate(new BondTerms(
                        "NEG", good.valueDate(), good.maturityDate(),
                        good.frequency(), good.stubType(), good.dayCount(),
                        good.eom(), good.convention(), good.exDividendBusinessDays(),
                        good.rateSteps(),
                        List.of(new NotionalStep(good.valueDate(),
                                new BigDecimal("-1.00"))),
                        good.calendar())));

        a.throwsCode("rising notional", ErrorCode.CONFLICTING_TERMS,
                () -> TermsValidator.validate(new BondTerms(
                        "RISE", good.valueDate(), good.maturityDate(),
                        good.frequency(), good.stubType(), good.dayCount(),
                        good.eom(), good.convention(), good.exDividendBusinessDays(),
                        good.rateSteps(),
                        List.of(
                                new NotionalStep(good.valueDate(), new BigDecimal("100.00")),
                                new NotionalStep(LocalDate.of(2026, 1, 15),
                                        new BigDecimal("120.00"))),
                        good.calendar())));

        a.throwsCode("stub none on irregular term", ErrorCode.CONFLICTING_TERMS,
                () -> TermsValidator.validate(copy(good,
                        LocalDate.of(2025, 1, 20), good.maturityDate())));

        a.throwsCode("rate steps not from value", ErrorCode.CONFLICTING_TERMS,
                () -> TermsValidator.validate(new BondTerms(
                        "LATE", good.valueDate(), good.maturityDate(),
                        good.frequency(), good.stubType(), good.dayCount(),
                        good.eom(), good.convention(), good.exDividendBusinessDays(),
                        List.of(new RateStep(LocalDate.of(2025, 2, 15),
                                new BigDecimal("0.05"))),
                        good.notionalSteps(), good.calendar())));

        a.throwsCode("descending rate steps", ErrorCode.DATE_ORDER,
                () -> TermsValidator.validate(new BondTerms(
                        "DUP", good.valueDate(), good.maturityDate(),
                        good.frequency(), good.stubType(), good.dayCount(),
                        good.eom(), good.convention(), good.exDividendBusinessDays(),
                List.of(
                        new RateStep(good.valueDate(), new BigDecimal("0.05")),
                        new RateStep(good.valueDate(), new BigDecimal("0.04"))),
                        good.notionalSteps(), good.calendar())));
    }

    private static BondTerms copy(BondTerms source, LocalDate value, LocalDate maturity) {
        return new BondTerms(source.id(), value, maturity,
                source.frequency(), source.stubType(), source.dayCount(),
                source.eom(), source.convention(), source.exDividendBusinessDays(),
                source.rateSteps(), source.notionalSteps(), source.calendar());
    }
}
