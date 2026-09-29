package csm.engine;

import csm.model.GroupRoll;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import csm.model.Account;
import csm.model.Balances;
import csm.model.JournalEntry;
import csm.model.JournalLine;
import csm.model.StepRoll;
import csm.util.DecimalScale;

/**
 * 机械 oracle：逐项验证期末 CSM 与亏损组成滚动闭合、分录借贷平衡，
 * 并验证逐组汇总等于分录明细。
 */
public final class RollOracle {

    public void verify(GroupRoll roll) {
        if (roll == null) {
            throw new CsmValidationException("roll must not be null");
        }
        Balances expectedOpening = roll.opening();
        for (StepRoll step : roll.steps()) {
            expectEquals(step.opening(), expectedOpening,
                    roll.groupId() + " period " + roll.periodIndex()
                            + " step " + step.stepName() + " opening chaining");
            Balances computedClosing = new Balances(
                    step.opening().csm().add(step.movement().csm()),
                    step.opening().lossComponent().add(step.movement().lossComponent()),
                    step.opening().fulfillmentCashFlows()
                            .add(step.movement().fulfillmentCashFlows()));
            expectEquals(computedClosing, step.closing(),
                    roll.groupId() + " period " + roll.periodIndex()
                            + " step " + step.stepName() + " opening+movement=closing");
            requireNonNegative(step.closing(),
                    roll.groupId() + " period " + roll.periodIndex()
                            + " step " + step.stepName() + " non-negative closing");
            expectedOpening = step.closing();
        }
        expectEquals(expectedOpening, roll.closing(),
                roll.groupId() + " period " + roll.periodIndex() + " final closing");

        Map<Account, BigDecimal> detailedDebit = new HashMap<>();
        Map<Account, BigDecimal> detailedCredit = new HashMap<>();
        for (JournalEntry entry : roll.entries()) {
            verifyEntryBalanced(entry);
            for (JournalLine line : entry.lines()) {
                detailedDebit.merge(line.account(), line.debit(), BigDecimal::add);
                detailedCredit.merge(line.account(), line.credit(), BigDecimal::add);
            }
        }
        Map<Account, BigDecimal> stepDebit = new HashMap<>();
        Map<Account, BigDecimal> stepCredit = new HashMap<>();
        for (StepRoll step : roll.steps()) {
            for (JournalEntry entry : step.entries()) {
                for (JournalLine line : entry.lines()) {
                    stepDebit.merge(line.account(), line.debit(), BigDecimal::add);
                    stepCredit.merge(line.account(), line.credit(), BigDecimal::add);
                }
            }
        }
        expectAccountTotalsEqual(detailedDebit, stepDebit, roll.groupId(), "debit");
        expectAccountTotalsEqual(detailedCredit, stepCredit, roll.groupId(), "credit");
    }

    public void verifyAll(List<GroupRoll> rolls) {
        if (rolls == null || rolls.isEmpty()) {
            throw new CsmValidationException("roll list must not be empty");
        }
        Map<String, Map<Account, BigDecimal>> groupDebit = new HashMap<>();
        Map<String, Map<Account, BigDecimal>> groupCredit = new HashMap<>();
        GroupRoll previous = null;
        for (GroupRoll roll : rolls) {
            verify(roll);
            if (previous != null
                    && previous.groupId().equals(roll.groupId())
                    && previous.periodIndex() + 1 == roll.periodIndex()) {
                expectEquals(roll.opening(), previous.closing(),
                        roll.groupId() + " period " + roll.periodIndex() + " opening carry-forward");
            }
            previous = roll;
            Map<Account, BigDecimal> debit =
                    groupDebit.computeIfAbsent(roll.groupId(), key -> new HashMap<>());
            Map<Account, BigDecimal> credit =
                    groupCredit.computeIfAbsent(roll.groupId(), key -> new HashMap<>());
            for (JournalEntry entry : roll.entries()) {
                for (JournalLine line : entry.lines()) {
                    debit.merge(line.account(), line.debit(), BigDecimal::add);
                    credit.merge(line.account(), line.credit(), BigDecimal::add);
                }
            }
        }

        Map<Account, BigDecimal> summaryDebit = new HashMap<>();
        Map<Account, BigDecimal> summaryCredit = new HashMap<>();
        for (GroupRoll roll : rolls) {
            for (JournalEntry entry : roll.entries()) {
                for (JournalLine line : entry.lines()) {
                    summaryDebit.merge(line.account(), line.debit(), BigDecimal::add);
                    summaryCredit.merge(line.account(), line.credit(), BigDecimal::add);
                }
            }
        }
        Map<Account, BigDecimal> groupedSummaryDebit = new HashMap<>();
        Map<Account, BigDecimal> groupedSummaryCredit = new HashMap<>();
        for (Map<Account, BigDecimal> totals : groupDebit.values()) {
            totals.forEach((account, amount) ->
                    groupedSummaryDebit.merge(account, amount, BigDecimal::add));
        }
        for (Map<Account, BigDecimal> totals : groupCredit.values()) {
            totals.forEach((account, amount) ->
                    groupedSummaryCredit.merge(account, amount, BigDecimal::add));
        }
        expectAccountTotalsEqual(summaryDebit, groupedSummaryDebit, "ALL-GROUPS", "debit");
        expectAccountTotalsEqual(summaryCredit, groupedSummaryCredit, "ALL-GROUPS", "credit");
    }

    private static void verifyEntryBalanced(JournalEntry entry) {
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (JournalLine line : entry.lines()) {
            debit = debit.add(line.debit());
            credit = credit.add(line.credit());
        }
        debit = DecimalScale.normalize(debit);
        credit = DecimalScale.normalize(credit);
        if (debit.compareTo(credit) != 0) {
            throw new CsmValidationException(
                    "unbalanced entry " + entry.step() + "/" + entry.reference()
                            + " debit=" + debit + " credit=" + credit);
        }
    }

    private static void requireNonNegative(Balances balances, String context) {
        if (balances.csm().signum() < 0 || balances.lossComponent().signum() < 0
                || balances.fulfillmentCashFlows().signum() < 0) {
            throw new CsmValidationException(context + ": " + balances);
        }
    }

    private static void expectEquals(Balances actual, Balances expected, String context) {
        if (actual.csm().compareTo(expected.csm()) != 0
                || actual.lossComponent().compareTo(expected.lossComponent()) != 0
                || actual.fulfillmentCashFlows().compareTo(expected.fulfillmentCashFlows()) != 0) {
            throw new CsmValidationException(
                    "roll closure failed [" + context + "] expected=" + expected
                            + " actual=" + actual);
        }
    }

    private static void expectAccountTotalsEqual(Map<Account, BigDecimal> a,
                                                 Map<Account, BigDecimal> b,
                                                 String groupId, String side) {
        for (Account account : Account.values()) {
            BigDecimal va = DecimalScale.normalize(a.getOrDefault(account, zero()));
            BigDecimal vb = DecimalScale.normalize(b.getOrDefault(account, zero()));
            if (va.compareTo(vb) != 0) {
                throw new CsmValidationException(
                        "group summary mismatch [" + groupId + "/" + side + "/" + account
                                + "] " + va + " != " + vb);
            }
        }
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(DecimalScale.SCALE);
    }
}
