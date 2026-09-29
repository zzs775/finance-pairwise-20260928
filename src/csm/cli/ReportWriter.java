package csm.cli;

import csm.model.Amount;
import csm.model.BalanceTriple;
import csm.model.GroupRollforward;
import csm.model.JournalEntry;
import csm.model.Posting;
import csm.model.StepResult;

import java.io.PrintStream;

/** 文本渲染：逐组逐步骤打印期初/变动/期末与借贷分录。 */
final class ReportWriter {

    private ReportWriter() {
    }

    static void write(PrintStream out, GroupRollforward rollforward) {
        out.printf("GROUP %s | PERIOD %s | APPLIED RULE %s%n",
                rollforward.groupId(), rollforward.periodId(),
                rollforward.appliedVersion().code());
        for (StepResult step : rollforward.steps()) {
            out.printf("  STEP %s%n", step.type());
            printTriple(out, "    FCF ", step.fulfillmentCashFlow());
            printTriple(out, "    CSM ", step.csm());
            printTriple(out, "    LC  ", step.lossComponent());
            for (JournalEntry entry : step.entries()) {
                out.printf("      ENTRY %-26s %s%n", entry.movementRef(),
                        entry.entryId());
                for (Posting line : entry.lines()) {
                    out.printf("        %-28s D=%-12s C=%s%n",
                            line.account(),
                            signed(line.debit()),
                            signed(line.credit()));
                }
            }
        }
        out.printf("  CLOSING FCF=%s CSM=%s LC=%s%n%n",
                rollforward.closingFcf(),
                rollforward.closingCsm(),
                rollforward.closingLossComponent());
    }

    private static void printTriple(PrintStream out, String label,
                                    BalanceTriple triple) {
        out.printf("%s opening=%s movement=%s closing=%s%n",
                label, triple.opening(), triple.movement(),
                triple.closing());
    }

    private static String signed(Amount value) {
        return value.isZero() ? "-" : value.toString();
    }
}
