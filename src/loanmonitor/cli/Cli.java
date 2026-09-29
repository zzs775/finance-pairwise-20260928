package loanmonitor.cli;

import java.nio.file.Path;
import java.time.LocalDate;

import loanmonitor.DailyOracle;
import loanmonitor.Loader;
import loanmonitor.MonitoringEngine;
import loanmonitor.model.MonitoringSnapshot;

/**
 * Command line entry point.
 *   run     <input> <asOf>           snapshot canonical text as of a date
 *   oracle  <input> <asOf>           same day-by-day path (explicit oracle)
 *   rebuild <input> <asOf>           full rebuild canonical text
 * Output is deterministic and independent of input ordering.
 */
public final class Cli {

    private Cli() {
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            usage();
            System.exit(2);
        }
        String command = args[0];
        Loader.LoadedInput input = Loader.load(Path.of(args[1]));
        LocalDate asOf = LocalDate.parse(args[2]);

        MonitoringEngine engine = new MonitoringEngine(
                input.inception(), input.periods(), input.covenants(),
                input.facts(), input.payments(), input.waivers());

        MonitoringSnapshot snapshot = switch (command) {
            case "run", "oracle" -> {
                DailyOracle oracle = new DailyOracle(engine);
                yield oracle.runThrough(input.inception(), asOf);
            }
            case "rebuild" -> {
                DailyOracle oracle = new DailyOracle(engine);
                yield oracle.rebuild(input.inception(), asOf);
            }
            default -> {
                usage();
                System.exit(2);
                yield null;
            }
        };
        System.out.print(snapshot.toCanonicalText());
    }

    private static void usage() {
        System.err.println("usage: <run|oracle|rebuild> <input-file> <asOf-yyyy-MM-dd>");
    }
}
