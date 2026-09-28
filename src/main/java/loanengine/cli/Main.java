package loanengine.cli;

import loanengine.dates.DayCount;
import loanengine.dates.DayConvention;
import loanengine.dates.Frequency;
import loanengine.engine.CashflowEngine;
import loanengine.engine.CashflowRow;
import loanengine.model.Contract;
import loanengine.rate.ResetSpec;
import loanengine.schedule.AmortizationType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || has(args, "help")) {
            printUsage();
            return;
        }
        try {
            Contract contract = parse(args);
            List<CashflowRow> rows = new CashflowEngine().generate(contract);
            printCsv(rows);
        } catch (RuntimeException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(2);
        }
    }

    private static boolean has(String[] args, String key) {
        for (String arg : args) {
            if (arg.equals("--" + key)) {
                return true;
            }
        }
        return false;
    }

    private static Contract parse(String[] args) {
        java.util.Map<String, String> opts = new java.util.HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IllegalArgumentException("bad argument: " + arg);
            }
            int eq = arg.indexOf('=');
            opts.put(arg.substring(2, eq), arg.substring(eq + 1));
        }
        Contract.Builder builder = Contract.builder()
                .notional(new BigDecimal(require(opts, "notional")))
                .valueDate(LocalDate.parse(require(opts, "valueDate")))
                .maturityDate(LocalDate.parse(require(opts, "maturityDate")))
                .amortizationType(AmortizationType.valueOf(
                        opts.getOrDefault("amortization", "EQUAL_PAYMENT")))
                .frequency(Frequency.valueOf(
                        opts.getOrDefault("frequency", "MONTHLY")))
                .dayCount(DayCount.valueOf(opts.getOrDefault("dayCount", "ACT_365F")))
                .reset(ResetSpec.fixed(new BigDecimal(
                        opts.getOrDefault("rate", "0"))))
                .dayConvention(DayConvention.valueOf(
                        opts.getOrDefault("convention", "NONE")))
                .monthEndRule(Boolean.parseBoolean(opts.getOrDefault("monthEnd", "false")));
        if (opts.containsKey("firstPaymentDate")) {
            builder.firstPaymentDate(LocalDate.parse(opts.get("firstPaymentDate")));
        }
        return builder.build();
    }

    private static String require(java.util.Map<String, String> opts, String key) {
        String value = opts.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing required --" + key);
        }
        return value;
    }

    private static void printCsv(List<CashflowRow> rows) {
        System.out.println("period,accrualStart,accrualEnd,paymentDate,opening,"
                + "advance,rate,interest,scheduledPrincipal,prepayment,totalPayment,"
                + "closing,fixingVersion,recast");
        for (CashflowRow row : rows) {
            System.out.printf(
                    "%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%d,%b%n",
                    row.period(), row.accrualStart(), row.accrualEnd(),
                    row.paymentDate(), row.openingBalance(), row.advance(),
                    row.interestRate().toPlainString(), row.interest(),
                    row.scheduledPrincipal(), row.prepayment(), row.totalPayment(),
                    row.closingBalance(), row.fixingVersion(), row.recast());
        }
    }

    private static void printUsage() {
        System.out.println("""
                Loan cashflow engine (fixed rate CLI)
                Required:
                  --notional=10000.00 --valueDate=2025-01-31 --maturityDate=2025-07-31
                Optional:
                  --rate=0.06 --amortization=EQUAL_PAYMENT|EQUAL_PRINCIPAL|BULLET
                  --frequency=MONTHLY|QUARTERLY|SEMI_ANNUAL|ANNUAL
                  --dayCount=ACT_365F|ACT_360|THIRTY_360
                  --convention=NONE|FOLLOWING|MODIFIED_FOLLOWING
                  --monthEnd=true|false --firstPaymentDate=2025-04-15
                Output is CSV on stdout.
                """);
    }
}
