package fi;

import fi.io.Codec;
import fi.io.Json;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class CodecTest {

    private CodecTest() {
    }

    static void run(Tests.Asserts a) {
        roundtrip(a, SampleBonds.regular());
        roundtrip(a, SampleBonds.shortFinal());
        roundtrip(a, SampleBonds.longInitial());
        roundtrip(a, SampleBonds.eomAmortizing());

        oldVersionDefaults(a);
        unknownFieldRejected(a);
        futureVersionRejected(a);
        canonicalStable(a);
    }

    private static void roundtrip(Tests.Asserts a, BondTerms terms) {
        CashFlowSchedule schedule = ScheduleGenerator.generate(terms);
        byte[] first = Codec.encode(schedule);
        CashFlowSchedule decoded = Codec.decode(first);
        byte[] second = Codec.encode(decoded);

        a.that(terms.id() + " byte-idempotent roundtrip",
                Arrays.equals(first, second));
        a.equal(terms.id() + " period count preserved",
                decoded.periods().size(), schedule.periods().size());
        a.equal(terms.id() + " cashflow count preserved",
                decoded.cashFlows().size(), schedule.cashFlows().size());

        for (int i = 0; i < schedule.periods().size(); i++) {
            AccrualPeriod expected = schedule.periods().get(i);
            AccrualPeriod actual = decoded.periods().get(i);
            a.equal(terms.id() + " period start preserved " + i,
                    actual.unadjustedStart(), expected.unadjustedStart());
            a.equal(terms.id() + " period end preserved " + i,
                    actual.unadjustedEnd(), expected.unadjustedEnd());
            a.equal(terms.id() + " payment preserved " + i,
                    actual.paymentDate(), expected.paymentDate());
            a.that(terms.id() + " coupon preserved " + i,
                    actual.couponAmount().compareTo(expected.couponAmount()) == 0);
            a.equal(terms.id() + " segments preserved " + i,
                    actual.segments().size(), expected.segments().size());
        }
        for (int i = 0; i < schedule.cashFlows().size(); i++) {
            a.equal(terms.id() + " cashflow order preserved " + i,
                    decoded.cashFlows().get(i).paymentDate(),
                    schedule.cashFlows().get(i).paymentDate());
            a.that(terms.id() + " cashflow amount preserved " + i,
                    decoded.cashFlows().get(i).amount()
                            .compareTo(schedule.cashFlows().get(i).amount()) == 0);
        }
    }

    private static void oldVersionDefaults(Tests.Asserts a) {
        byte[] encoded = Codec.encode(
                ScheduleGenerator.generate(SampleBonds.regular()));
        String text = new String(encoded, StandardCharsets.UTF_8);
        String stripped = text.replace(",\"exDividendBusinessDays\":3", "");
        a.that("stripped field for simulation",
                !stripped.equals(text) && stripped.contains("\"version\":1"));
        CashFlowSchedule decoded =
                Codec.decode(stripped.getBytes(StandardCharsets.UTF_8));
        a.equal("missing exDividend field defaults to frozen zero",
                decoded.terms().exDividendBusinessDays(), 0);
        a.equal("eom frozen default false",
                decoded.terms().eom(), false);
    }

    private static void unknownFieldRejected(Tests.Asserts a) {
        byte[] encoded = Codec.encode(
                ScheduleGenerator.generate(SampleBonds.regular()));
        String text = new String(encoded, StandardCharsets.UTF_8);
        String tampered = text.replaceFirst("\"format\"",
                "\"futureField\":123,\"format\"");
        a.throwsCode("unknown root field rejected",
                ErrorCode.INVALID_TERMS,
                () -> Codec.decode(tampered.getBytes(StandardCharsets.UTF_8)));

        String badTerms = text.replaceFirst("\"dayCount\"",
                "\"mystery\":\"x\",\"dayCount\"");
        a.throwsCode("unknown terms field rejected",
                ErrorCode.INVALID_TERMS,
                () -> Codec.decode(badTerms.getBytes(StandardCharsets.UTF_8)));
    }

    private static void futureVersionRejected(Tests.Asserts a) {
        byte[] encoded = Codec.encode(
                ScheduleGenerator.generate(SampleBonds.regular()));
        String text = new String(encoded, StandardCharsets.UTF_8);
        String future = text.replace("\"version\":1", "\"version\":99");
        a.throwsCode("future version rejected",
                ErrorCode.INVALID_TERMS,
                () -> Codec.decode(future.getBytes(StandardCharsets.UTF_8)));
    }

    private static void canonicalStable(Tests.Asserts a) {
        Map<String, Object> outOfOrder = Map.of(
                "b", List.of(1, 2),
                "a", Map.of("z", 1, "a", 2));
        String canonical = Json.writeCanonical(outOfOrder);
        a.equal("keys sorted and arrays ordered",
                canonical, "{\"a\":{\"a\":2,\"z\":1},\"b\":[1,2]}");
        Object reparsed = Json.parse(canonical);
        a.equal("canonical json reparses to same text",
                Json.writeCanonical(reparsed), canonical);
    }
}
