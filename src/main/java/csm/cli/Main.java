package csm.cli;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import csm.engine.CsmRollEngine;
import csm.engine.CsmValidationException;
import csm.engine.ReplayEngine;
import csm.engine.RollOracle;
import csm.model.Account;
import csm.model.Balances;
import csm.model.ContractGroup;
import csm.model.CoveragePeriodUnits;
import csm.model.Direction;
import csm.model.GroupRoll;
import csm.model.JournalEntry;
import csm.model.JournalLine;
import csm.model.MovementEvent;
import csm.model.MovementType;
import csm.model.RuleVersion;
import csm.model.StepRoll;

/**
 * 命令行入口：运行合成 fixture 滚动并打印结果与 oracle 校验结论。
 */
public final class Main {

    private static final String LINE =
            "------------------------------------------------------------------------";

    public static void main(String[] args) {
        ContractGroup normal = demoGroup();
        int periods = 3;

        System.out.println("CSM roll-forward engine (frozen in-question rules)");
        System.out.println("scale = 8, HALF_UP; signs: adverse=+, favourable=-, settlement=+");
        System.out.println(LINE);

        List<GroupRoll> rolls = new ReplayEngine(Map.of(
                        0, RuleVersion.V1,
                        1, RuleVersion.V1,
                        2, RuleVersion.V2))
                .replay(normal, periods);

        for (GroupRoll roll : rolls) {
            printRoll(roll);
        }
        new RollOracle().verifyAll(rolls);
        System.out.println(LINE);
        System.out.println("ORACLE: all step closures, non-negative CSM/loss, balanced journals");
        System.out.println("        and group-summary-vs-detail reconciliations verified.");

        demonstrateRejections();
    }

    private static ContractGroup demoGroup() {
        return new ContractGroup(
                "DEMO",
                new Balances(bd("100"), bd("0"), bd("300")),
                List.of(
                        event(0, MovementType.FUTURE_SERVICE_CHANGE, "-15", Direction.FAVORABLE,
                                RuleVersion.V1, "fav-experience"),
                        event(0, MovementType.CASH_SETTLEMENT, "40", Direction.NEUTRAL,
                                RuleVersion.V1, "claims-paid"),
                        event(1, MovementType.FUTURE_SERVICE_CHANGE, "130", Direction.ADVERSE,
                                RuleVersion.V1, "adverse-assumption"),
                        event(1, MovementType.CASH_SETTLEMENT, "30", Direction.NEUTRAL,
                                RuleVersion.V1, "claims-paid"),
                        event(2, MovementType.FUTURE_SERVICE_CHANGE, "-40", Direction.FAVORABLE,
                                RuleVersion.V2, "fav-recovery"),
                        event(2, MovementType.CASH_SETTLEMENT, "30", Direction.NEUTRAL,
                                RuleVersion.V2, "claims-paid")),
                List.of(
                        new CoveragePeriodUnits(0, bd("10"), bd("30")),
                        new CoveragePeriodUnits(1, bd("10"), bd("20")),
                        new CoveragePeriodUnits(2, bd("10"), bd("10"))));
    }

    private static MovementEvent event(int period, MovementType type, String amount,
                                       Direction direction, RuleVersion version, String ref) {
        return new MovementEvent(period, type, bd(amount), direction, version, ref);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static void printRoll(GroupRoll roll) {
        System.out.println("group=" + roll.groupId()
                + " period=" + roll.periodIndex()
                + " rule=" + roll.ruleVersion());
        System.out.printf("  opening  CSM=%-14s LOSS=%-14s FCF=%-14s%n",
                roll.opening().csm().toPlainString(),
                roll.opening().lossComponent().toPlainString(),
                roll.opening().fulfillmentCashFlows().toPlainString());
        for (StepRoll step : roll.steps()) {
            System.out.printf("  %-22s move CSM=%-13s LOSS=%-13s FCF=%-13s -> close CSM=%-13s"
                            + " LOSS=%-13s FCF=%s%n",
                    step.stepName(),
                    step.movement().csm().toPlainString(),
                    step.movement().lossComponent().toPlainString(),
                    step.movement().fulfillmentCashFlows().toPlainString(),
                    step.closing().csm().toPlainString(),
                    step.closing().lossComponent().toPlainString(),
                    step.closing().fulfillmentCashFlows().toPlainString());
            for (JournalEntry entry : step.entries()) {
                System.out.println("      journal " + entry.reference() + " : "
                        + summarize(entry));
            }
        }
        System.out.printf("  closing  CSM=%-14s LOSS=%-14s FCF=%-14s%n",
                roll.closing().csm().toPlainString(),
                roll.closing().lossComponent().toPlainString(),
                roll.closing().fulfillmentCashFlows().toPlainString());
        System.out.println(LINE);
    }

    private static String summarize(JournalEntry entry) {
        StringBuilder sb = new StringBuilder();
        for (JournalLine line : entry.lines()) {
            if (line.debit().signum() > 0) {
                sb.append("Dr ").append(line.account()).append(' ').append(line.debit())
                        .append("  ");
            } else {
                sb.append("Cr ").append(line.account()).append(' ').append(line.credit())
                        .append("  ");
            }
        }
        return sb.toString().trim();
    }

    private static void demonstrateRejections() {
        System.out.println();
        System.out.println("Rejection examples:");
        ContractGroup badSigns = new ContractGroup(
                "BAD-SIGNS",
                new Balances(bd("50"), bd("0"), bd("100")),
                List.of(new MovementEvent(0, MovementType.FUTURE_SERVICE_CHANGE, bd("20"),
                        Direction.FAVORABLE, RuleVersion.V1, "fav-tagged-positive")),
                List.of(new CoveragePeriodUnits(0, bd("10"), bd("20"))));
        tryRejection(badSigns, 1, "sign conflict");

        ContractGroup zeroUnits = new ContractGroup(
                "ZERO",
                new Balances(bd("50"), bd("0"), bd("100")),
                List.of(),
                List.of(new CoveragePeriodUnits(0, bd("0"), bd("20"))));
        tryRejection(zeroUnits, 1, "zero coverage units");
    }

    private static void tryRejection(ContractGroup group, int periods, String label) {
        try {
            new CsmRollEngine().rollAllPeriods(group, periods);
            System.out.println("  UNEXPECTED ACCEPTANCE: " + label);
        } catch (CsmValidationException expected) {
            System.out.println("  rejected [" + label + "]: " + expected.getMessage());
        }
    }

    private Main() {
    }
}
