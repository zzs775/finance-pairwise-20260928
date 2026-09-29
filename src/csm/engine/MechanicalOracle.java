package csm.engine;

import csm.model.Amount;
import csm.model.BalanceTriple;
import csm.model.GroupRollforward;
import csm.model.JournalEntry;
import csm.model.OracleException;
import csm.model.Posting;
import csm.model.StepResult;
import csm.model.StepType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 机械校验：
 * 1) 每步 期初+变动=期末，上一步期末=下一步期初；
 * 2) CSM 与亏损组成非负；
 * 3) 每笔分录借贷平衡；
 * 4) 组级各科目滚动合计 = 分录明细净发生额。
 */
public final class MechanicalOracle {

    private static final List<StepType> FIXED_ORDER = List.of(
            StepType.INTEREST_ACCRETION,
            StepType.FUTURE_SERVICE_CHANGE,
            StepType.SERVICE_RELEASE,
            StepType.SETTLEMENT);

    private static final Map<StepType, String> ACCOUNT_OF =
            new EnumMap<>(Map.of(
                    StepType.INTEREST_ACCRETION, Accounts.FCF_LIABILITY,
                    StepType.FUTURE_SERVICE_CHANGE, Accounts.FCF_LIABILITY,
                    StepType.SERVICE_RELEASE, Accounts.FCF_LIABILITY,
                    StepType.SETTLEMENT, Accounts.FCF_LIABILITY));

    public void verify(GroupRollforward rollforward) {
        List<StepResult> steps = rollforward.steps();
        if (steps.size() != FIXED_ORDER.size()) {
            throw new OracleException("expected " + FIXED_ORDER.size()
                    + " steps, got " + steps.size());
        }

        for (int index = 0; index < steps.size(); index++) {
            StepResult step = steps.get(index);
            if (step.type() != FIXED_ORDER.get(index)) {
                throw new OracleException("step " + index
                        + " must be " + FIXED_ORDER.get(index)
                        + " but was " + step.type());
            }
            checkTriple(step.fulfillmentCashFlow(), "fcf", step);
            checkTriple(step.csm(), "csm", step);
            checkTriple(step.lossComponent(), "lossComponent", step);

            if (!step.csm().closing().isNonNegative()) {
                throw new OracleException("CSM went negative at "
                        + step.type());
            }
            if (!step.lossComponent().closing().isNonNegative()) {
                throw new OracleException("loss component went negative at "
                        + step.type());
            }

            for (JournalEntry entry : step.entries()) {
                if (!entry.isBalanced()) {
                    throw new OracleException("entry not balanced: "
                            + entry.entryId());
                }
            }

            if (index > 0) {
                checkChain(steps.get(index - 1), step);
            }
        }

        checkRollforwardTotals(rollforward);
    }

    private static void checkTriple(BalanceTriple triple, String label,
                                    StepResult step) {
        Amount expected = triple.opening().add(triple.movement());
        if (!expected.equals(triple.closing())) {
            throw new OracleException(label + " not closed at "
                    + step.type() + ": " + triple.opening() + " + "
                    + triple.movement() + " != " + triple.closing());
        }
    }

    private static void checkChain(StepResult previous, StepResult current) {
        if (!previous.fulfillmentCashFlow().closing()
                .equals(current.fulfillmentCashFlow().opening())) {
            throw new OracleException("fcf chain broken between "
                    + previous.type() + " and " + current.type());
        }
        if (!previous.csm().closing().equals(current.csm().opening())) {
            throw new OracleException("csm chain broken between "
                    + previous.type() + " and " + current.type());
        }
        if (!previous.lossComponent().closing()
                .equals(current.lossComponent().opening())) {
            throw new OracleException("loss component chain broken between "
                    + previous.type() + " and " + current.type());
        }
    }

    private static void checkRollforwardTotals(GroupRollforward rollforward) {
        Amount fcfMovement = Amount.zero();
        Amount csmMovement = Amount.zero();
        Amount lcMovement = Amount.zero();
        for (StepResult step : rollforward.steps()) {
            fcfMovement = fcfMovement.add(step.fulfillmentCashFlow()
                    .movement());
            csmMovement = csmMovement.add(step.csm().movement());
            lcMovement = lcMovement.add(step.lossComponent().movement());
        }

        if (!rollforward.input().opening().fulfillmentCashFlow()
                .add(fcfMovement).equals(rollforward.closingFcf())) {
            throw new OracleException("group fcf total does not roll");
        }
        if (!rollforward.input().opening().csm().add(csmMovement)
                .equals(rollforward.closingCsm())) {
            throw new OracleException("group csm total does not roll");
        }
        if (!rollforward.input().opening().lossComponent().add(lcMovement)
                .equals(rollforward.closingLossComponent())) {
            throw new OracleException("group loss component total does not roll");
        }

        Map<String, Amount> credits = new java.util.HashMap<>();
        Map<String, Amount> debits = new java.util.HashMap<>();
        for (JournalEntry entry : rollforward.allEntries()) {
            for (Posting line : entry.lines()) {
                credits.merge(line.account(), line.credit(), Amount::add);
                debits.merge(line.account(), line.debit(), Amount::add);
            }
        }

        checkLedgerNet(credits, debits, Accounts.FCF_LIABILITY,
                fcfMovement, "fcf");
        checkLedgerNet(credits, debits, Accounts.CSM_LIABILITY,
                csmMovement, "csm");
        checkLedgerNet(credits, debits, Accounts.LOSS_COMPONENT_MEMO,
                lcMovement, "lossComponent");

        Amount totalDebits = Amount.zero();
        Amount totalCredits = Amount.zero();
        for (Amount value : debits.values()) {
            totalDebits = totalDebits.add(value);
        }
        for (Amount value : credits.values()) {
            totalCredits = totalCredits.add(value);
        }
        if (!totalDebits.equals(totalCredits)) {
            throw new OracleException(
                    "aggregate journal debits/credits diverge");
        }
    }

    private static void checkLedgerNet(Map<String, Amount> credits,
                                       Map<String, Amount> debits,
                                       String account,
                                       Amount expectedMovement,
                                       String label) {
        Amount net = credits.getOrDefault(account, Amount.zero())
                .subtract(debits.getOrDefault(account, Amount.zero()));
        if (!net.equals(expectedMovement)) {
            throw new OracleException(label
                    + " roll movement does not equal journal detail net: "
                    + expectedMovement + " vs " + net);
        }
    }
}
