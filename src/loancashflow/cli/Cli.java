package loancashflow.cli;

import loancashflow.engine.ScheduleEngine;
import loancashflow.model.AmortizationMethod;
import loancashflow.model.BusinessDayConvention;
import loancashflow.model.Contract;
import loancashflow.model.DayCount;
import loancashflow.model.FixingObservation;
import loancashflow.model.FixingTable;
import loancashflow.model.Frequency;
import loancashflow.model.HolidayCalendar;
import loancashflow.model.Prepayment;
import loancashflow.model.RateSpec;
import loancashflow.model.RecastMode;
import loancashflow.model.ResetSpec;
import loancashflow.model.ScheduleResult;
import loancashflow.model.StubConvention;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.PrintStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Command-line interface.
 * {@code demo}        prints a built-in fixed and floating schedule;
 * {@code run <file>}   reads a contract spec and prints a CSV cash-flow plan.
 */
public final class Cli {

    private Cli() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        ScheduleEngine engine = new ScheduleEngine();
        try {
            if (args.length == 0 || args[0].equals("demo")) {
                out.println("# fixed annuity demo");
                writeCsv(demoFixed(engine), out);
                out.println();
                out.println("# floating bullet with fixing demo");
                writeCsv(demoFloating(engine), out);
                return 0;
            }
            if (args[0].equals("run") && args.length == 2) {
                List<String> lines = Files.readAllLines(Path.of(args[1]));
                writeCsv(engine.generate(parseSpec(lines)), out);
                return 0;
            }
            err.println("usage: loan-cashflow [demo | run <spec-file>]");
            return 2;
        } catch (IllegalArgumentException | IOException exception) {
            err.println("error: " + exception.getMessage());
            return 1;
        } catch (loancashflow.engine.LoanException exception) {
            err.println("error [" + exception.code() + "]: " + exception.getMessage());
            return 1;
        }
    }

    static ScheduleResult demoFixed(ScheduleEngine engine) {
        Contract contract = Contract.create(
                "demo-fixed", new BigDecimal("100000.00"),
                LocalDate.of(2024, 1, 31), LocalDate.of(2024, 4, 30),
                LocalDate.of(2025, 4, 30),
                Frequency.QUARTERLY, DayCount.ACT_365F,
                AmortizationMethod.ANNUITY,
                true, StubConvention.NONE, BusinessDayConvention.MODIFIED_FOLLOWING,
                HolidayCalendar.of(java.util.Set.of(LocalDate.of(2024, 7, 1))),
                RateSpec.fixed(new BigDecimal("0.0625")), null,
                List.of(new Prepayment(4, new BigDecimal("10000.00"), RecastMode.KEEP_TERM)));
        return engine.generate(contract);
    }

    static ScheduleResult demoFloating(ScheduleEngine engine) {
        FixingTable table = FixingTable.of(
                new FixingObservation(LocalDate.of(2024, 1, 31), new BigDecimal("0.035"), 1),
                new FixingObservation(LocalDate.of(2024, 4, 30), new BigDecimal("0.038"), 1),
                new FixingObservation(LocalDate.of(2024, 7, 31), new BigDecimal("0.031"), 1),
                new FixingObservation(LocalDate.of(2024, 10, 31), new BigDecimal("0.040"), 1),
                new FixingObservation(LocalDate.of(2025, 1, 31), new BigDecimal("0.037"), 1));
        Contract contract = Contract.create(
                "demo-floating", new BigDecimal("500000.00"),
                LocalDate.of(2024, 1, 31), LocalDate.of(2024, 4, 30),
                LocalDate.of(2025, 4, 30),
                Frequency.QUARTERLY, DayCount.ACT_360,
                AmortizationMethod.EQUAL_PRINCIPAL,
                true, StubConvention.NONE, BusinessDayConvention.FOLLOWING,
                HolidayCalendar.of(java.util.Set.of()),
                RateSpec.floating(new BigDecimal("0.015"),
                        new BigDecimal("0.045"), new BigDecimal("0.075"),
                        new ResetSpec(LocalDate.of(2024, 1, 31), Frequency.QUARTERLY)),
                table, List.of());
        return engine.generate(contract);
    }

    static Contract parseSpec(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        List<FixingObservation> fixings = new ArrayList<>();
        List<Prepayment> prepayments = new ArrayList<>();
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0) {
                throw new IllegalArgumentException("cannot parse line: " + line);
            }
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            if (key.startsWith("fixing.")) {
                fixings.add(parseFixing(value));
            } else if (key.startsWith("prepayment.")) {
                prepayments.add(parsePrepayment(value));
            } else {
                values.put(key, value);
            }
        }

        boolean floating = Boolean.parseBoolean(values.getOrDefault("floating", "false"));
        RateSpec rateSpec;
        FixingTable fixingTable = null;
        if (floating) {
            rateSpec = RateSpec.floating(
                    new BigDecimal(values.getOrDefault("spread", "0")),
                    values.containsKey("floor") ? new BigDecimal(values.get("floor")) : null,
                    values.containsKey("cap") ? new BigDecimal(values.get("cap")) : null,
                    new ResetSpec(LocalDate.parse(values.get("firstResetDate")),
                            Frequency.valueOf(values.getOrDefault("resetFrequency", "QUARTERLY"))));
            fixingTable = FixingTable.ofList(fixings);
        } else {
            rateSpec = RateSpec.fixed(new BigDecimal(values.getOrDefault("fixedRate", "0")));
        }

        return Contract.create(
                values.getOrDefault("id", "spec"),
                new BigDecimal(values.get("nominal")),
                LocalDate.parse(values.get("valueDate")),
                LocalDate.parse(values.get("firstPaymentDate")),
                LocalDate.parse(values.get("maturityDate")),
                Frequency.valueOf(values.getOrDefault("frequency", "QUARTERLY")),
                DayCount.valueOf(values.getOrDefault("dayCount", "ACT_365F")),
                AmortizationMethod.valueOf(
                        values.getOrDefault("amortization", "ANNUITY")),
                Boolean.parseBoolean(values.getOrDefault("endOfMonthRule", "false")),
                StubConvention.valueOf(values.getOrDefault("stub", "NONE")),
                BusinessDayConvention.valueOf(
                        values.getOrDefault("businessDayConvention", "NONE")),
                HolidayCalendar.noHolidays(),
                rateSpec, fixingTable, prepayments);
    }

    private static FixingObservation parseFixing(String value) {
        String[] parts = value.split(",");
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "fixing must be 'resetDate,rawRate,version': " + value);
        }
        return new FixingObservation(LocalDate.parse(parts[0].trim()),
                new BigDecimal(parts[1].trim()), Integer.parseInt(parts[2].trim()));
    }

    private static Prepayment parsePrepayment(String value) {
        String[] parts = value.split(",");
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "prepayment must be 'periodOrdinal,amount,mode': " + value);
        }
        return new Prepayment(Integer.parseInt(parts[0].trim()),
                new BigDecimal(parts[1].trim()), RecastMode.valueOf(parts[2].trim()));
    }

    static void writeCsv(ScheduleResult result, PrintStream out) {
        out.println("ordinal,accrualStart,accrualEnd,paymentDate,openingBalance,advance,"
                + "interestRate,yearFraction,interest,scheduledPrincipal,prepayment,"
                + "totalPrincipal,payment,closingBalance,resetDate");
        for (var row : result.rows()) {
            out.printf("%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                    row.ordinal(), row.accrualStart(), row.accrualEnd(), row.paymentDate(),
                    row.openingBalance().toPlainString(), row.advance().toPlainString(),
                    row.interestRate().toPlainString(), row.yearFraction().toPlainString(),
                    row.interest().toPlainString(), row.scheduledPrincipal().toPlainString(),
                    row.prepayment().toPlainString(), row.totalPrincipal().toPlainString(),
                    row.payment().toPlainString(), row.closingBalance().toPlainString(),
                    row.resetDate());
        }
    }
}
