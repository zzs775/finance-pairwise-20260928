package fi;

import fi.io.Codec;
import fi.model.DailyAccrual;
import java.time.LocalDate;
import java.util.Arrays;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        switch (args[0]) {
            case "list" -> listSamples();
            case "schedule" -> schedule(args);
            case "json" -> json(args);
            case "settle" -> settle(args);
            case "roundtrip" -> roundtrip(args);
            case "-h", "--help", "help" -> usage();
            default -> {
                System.err.println("unknown command: " + args[0]);
                usage();
                System.exit(2);
            }
        }
    }

    private static void usage() {
        System.out.println("""
                fixed-income schedule & accrual engine
                commands:
                  list
                  schedule  <sample-id>
                  json      <sample-id>
                  settle    <sample-id> <settlement-date YYYY-MM-DD>
                  roundtrip <sample-id>
                sample ids: regular, short-final, long-initial, eom-amort
                """);
    }

    private static void listSamples() {
        for (String id : SampleBonds.ids()) {
            BondTerms terms = SampleBonds.byId(id);
            System.out.printf("%-13s %s %s %s stub=%s eom=%s%n",
                    id,
                    terms.valueDate(),
                    terms.maturityDate(),
                    terms.frequency(),
                    terms.stubType(),
                    terms.eom());
        }
    }

    private static void schedule(String[] args) {
        CashFlowSchedule schedule = generate(args);
        System.out.println("# " + schedule.terms().id());
        System.out.println("idx start      end        payDate    notional     rate    yf          coupon");
        for (AccrualPeriod period : schedule.periods()) {
            System.out.printf("%3d %s %s %s %12s %7s %11s %12s%n",
                    period.index(),
                    period.unadjustedStart(),
                    period.unadjustedEnd(),
                    period.paymentDate(),
                    period.notional(),
                    period.annualRate(),
                    period.yearFraction(),
                    period.couponAmount());
        }
        System.out.println("cash flows:");
        for (CashFlow cashFlow : schedule.cashFlows()) {
            System.out.printf("  %s %-9s %12s period=%d%n",
                    cashFlow.paymentDate(),
                    cashFlow.type(),
                    cashFlow.amount(),
                    cashFlow.periodIndex());
        }
        System.out.println("ex-dividend windows:");
        for (ExDividendWindow window : ExDividend.windows(schedule)) {
            System.out.printf("  period=%d end=%s pay=%s ex=%s%n",
                    window.periodIndex(),
                    window.periodEndUnadjusted(),
                    window.paymentDate(),
                    window.exDate());
        }
    }

    private static void json(String[] args) {
        CashFlowSchedule schedule = generate(args);
        byte[] encoded = Codec.encode(schedule);
        System.out.println(new String(encoded, java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void settle(String[] args) {
        if (args.length < 3) {
            throw new IllegalArgumentException("settle requires <sample-id> <date>");
        }
        CashFlowSchedule schedule =
                ScheduleGenerator.generate(SampleBonds.byId(args[1]));
        LocalDate date = LocalDate.parse(args[2]);
        DailyAccrual daily = AccrualCalculator.accruedOn(schedule.terms(), schedule, date);
        SettlementInfo info = ExDividend.settle(schedule, date);
        System.out.printf(
                "settlement=%s period=%d accrualStart=%s accrualEnd=%s accrued=%s nextCouponToBuyer=%s%n",
                info.settlementDate(),
                info.currentPeriodIndex(),
                info.periodStart(),
                info.periodEnd(),
                info.accruedAmount(),
                info.nextCouponPayableToBuyer());
        System.out.printf("next coupon payable=%s amount=%s%n",
                daily.nextCouponPayableToBuyer(), daily.nextCouponAmount());
    }

    private static void roundtrip(String[] args) {
        CashFlowSchedule schedule = generate(args);
        byte[] first = Codec.encode(schedule);
        CashFlowSchedule decoded = Codec.decode(first);
        byte[] second = Codec.encode(decoded);
        if (!Arrays.equals(first, second)) {
            throw new IllegalStateException("canonical round trip is not byte-idempotent");
        }
        System.out.println("roundtrip OK, bytes=" + first.length);
        System.out.println(new String(first, java.nio.charset.StandardCharsets.UTF_8));
    }

    private static CashFlowSchedule generate(String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException("missing sample id");
        }
        return ScheduleGenerator.generate(SampleBonds.byId(args[1]));
    }
}
