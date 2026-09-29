package csm.engine;

import csm.model.Balances;
import csm.model.JournalEntry;
import csm.model.MovementEvent;
import csm.model.StepRoll;

import java.math.BigDecimal;
import java.util.List;

import csm.model.Account;
import csm.model.Direction;
import csm.model.MovementType;
import csm.util.DecimalScale;

/**
 * 单个题内处理步骤：产生独立的三态滚动与借贷分录，不用净额跳过中间状态。
 */
public final class StepProcessor {

    private final RuleBook ruleBook;
    private final JournalFactory journalFactory;

    public StepProcessor(RuleBook ruleBook) {
        if (ruleBook == null) {
            throw new CsmValidationException("rule book must not be null");
        }
        this.ruleBook = ruleBook;
        this.journalFactory = new JournalFactory();
    }

    /** 步骤1：利息展开（折现展开）。对期初 CSM 与 FCF 按冻结版本利率计息。 */
    public StepRoll interestAccretion(Balances opening, List<MovementEvent> events, String groupId) {
        Balances start = clean(opening);
        BigDecimal rate = ruleBook.accretionRate();
        BigDecimal csmAccretion = scale(start.csm().multiply(rate));
        BigDecimal fcfAccretion = scale(start.fulfillmentCashFlows().multiply(rate));

        List<JournalEntry> entries = new java.util.ArrayList<>();
        if (csmAccretion.signum() > 0) {
            entries.add(journalFactory.entry(groupId, periodOf(events), "INTEREST_ACCRETION", "CSM",
                    List.of(
                            journalFactory.debit(Account.CSM, csmAccretion),
                            journalFactory.credit(Account.INSURANCE_FINANCE_EXPENSE, csmAccretion))));
        }
        if (fcfAccretion.signum() > 0) {
            entries.add(journalFactory.entry(groupId, periodOf(events), "INTEREST_ACCRETION", "FCF",
                    List.of(
                            journalFactory.debit(Account.INSURANCE_FINANCE_EXPENSE, fcfAccretion),
                            journalFactory.credit(Account.FULFILLMENT_CASH_FLOWS, fcfAccretion))));
        }

        Balances movement = new Balances(csmAccretion, BigDecimal.ZERO.setScale(DecimalScale.SCALE),
                fcfAccretion);
        Balances closing = add(start, movement);
        return new StepRoll("INTEREST_ACCRETION", start, movement, closing, List.copyOf(entries));
    }

    /** 步骤2：未来服务变动（含风险调整变动）。不利先冲 CSM 再转亏损；有利先转回亏损再恢复 CSM。 */
    public StepRoll futureServiceChange(Balances opening, List<MovementEvent> events, String groupId) {
        Balances start = clean(opening);
        Balances current = start;
        List<JournalEntry> entries = new java.util.ArrayList<>();
        BigDecimal csmMove = zero();
        BigDecimal lossMove = zero();
        BigDecimal fcfMove = zero();

        for (MovementEvent event : events) {
            if (event.type() != MovementType.FUTURE_SERVICE_CHANGE
                    && event.type() != MovementType.RISK_ADJUSTMENT_CHANGE) {
                continue;
            }
            ruleBook.requireConsistentSign(event.type(), event.direction(), event.amount());
            BigDecimal magnitude = scale(event.amount().abs());
            if (magnitude.signum() == 0) {
                continue;
            }
            Balances delta;
            if (event.direction() == Direction.ADVERSE) {
                BigDecimal csmReduction = DecimalScale.min(current.csm(), magnitude);
                BigDecimal lossIncrease = magnitude.subtract(csmReduction);
                List<JournalEntry> eventEntries = new java.util.ArrayList<>();
                // 真实分录：不利变动增加负债并确认保险服务费用
                eventEntries.add(journalFactory.entry(groupId, event.periodIndex(),
                        "FUTURE_SERVICE_CHANGE", ref(event, "ADVERSE-PNL"),
                        List.of(
                                journalFactory.debit(Account.INSURANCE_SERVICE_EXPENSE, magnitude),
                                journalFactory.credit(Account.FULFILLMENT_CASH_FLOWS, magnitude))));
                if (csmReduction.signum() > 0) {
                    eventEntries.add(journalFactory.entry(groupId, event.periodIndex(),
                            "FUTURE_SERVICE_CHANGE", ref(event, "CSM-ABSORB"),
                            List.of(
                                    journalFactory.debit(Account.CSM, csmReduction),
                                    journalFactory.credit(Account.INSURANCE_SERVICE_EXPENSE,
                                            csmReduction))));
                }
                if (lossIncrease.signum() > 0) {
                    eventEntries.add(journalFactory.entry(groupId, event.periodIndex(),
                            "FUTURE_SERVICE_CHANGE", ref(event, "LOSS-RECOGNITION"),
                            List.of(
                                    journalFactory.credit(Account.LOSS_COMPONENT, lossIncrease),
                                    journalFactory.debit(Account.INSURANCE_SERVICE_EXPENSE,
                                            lossIncrease))));
                }
                delta = new Balances(csmReduction.negate(), lossIncrease, magnitude);
                entries.addAll(eventEntries);
            } else {
                BigDecimal lossReduction = DecimalScale.min(current.lossComponent(), magnitude);
                BigDecimal csmIncrease = magnitude.subtract(lossReduction);
                List<JournalEntry> eventEntries = new java.util.ArrayList<>();
                // 真实分录：有利变动减少负债并确认转回收益
                eventEntries.add(journalFactory.entry(groupId, event.periodIndex(),
                        "FUTURE_SERVICE_CHANGE", ref(event, "FAVOURABLE-PNL"),
                        List.of(
                                journalFactory.debit(Account.FULFILLMENT_CASH_FLOWS, magnitude),
                                journalFactory.credit(Account.LOSS_REVERSAL_INCOME, magnitude))));
                if (lossReduction.signum() > 0) {
                    eventEntries.add(journalFactory.entry(groupId, event.periodIndex(),
                            "FUTURE_SERVICE_CHANGE", ref(event, "LOSS-REVERSAL"),
                            List.of(
                                    journalFactory.debit(Account.LOSS_COMPONENT, lossReduction),
                                    journalFactory.credit(Account.LOSS_REVERSAL_INCOME,
                                            lossReduction))));
                }
                if (csmIncrease.signum() > 0) {
                    eventEntries.add(journalFactory.entry(groupId, event.periodIndex(),
                            "FUTURE_SERVICE_CHANGE", ref(event, "CSM-RESTORE"),
                            List.of(
                                    journalFactory.debit(Account.CSM,
                                            csmIncrease),
                                    journalFactory.credit(Account.LOSS_REVERSAL_INCOME,
                                            csmIncrease))));
                }
                delta = new Balances(csmIncrease, lossReduction.negate(), magnitude.negate());
                entries.addAll(eventEntries);
            }
            current = add(current, delta);
            csmMove = csmMove.add(delta.csm());
            lossMove = lossMove.add(delta.lossComponent());
            fcfMove = fcfMove.add(delta.fulfillmentCashFlows());
        }

        Balances movement = new Balances(scale(csmMove), scale(lossMove), scale(fcfMove));
        return new StepRoll("FUTURE_SERVICE_CHANGE", start, movement, current, List.copyOf(entries));
    }

    /** 步骤3：当期服务释放，按 当期覆盖单位/剩余总覆盖单位 释放期初 CSM。 */
    public StepRoll currentServiceRelease(Balances opening, List<MovementEvent> events,
                                         java.math.BigDecimal currentUnits,
                                         java.math.BigDecimal totalRemainingUnits,
                                         String groupId) {
        Balances start = clean(opening);
        if (currentUnits == null || totalRemainingUnits == null) {
            throw new CsmValidationException(
                    "coverage units must be provided for group " + groupId);
        }
        BigDecimal cu = DecimalScale.normalize(currentUnits);
        BigDecimal tu = DecimalScale.normalize(totalRemainingUnits);
        if (cu.signum() <= 0 || tu.signum() <= 0 || cu.compareTo(tu) > 0) {
            throw new CsmValidationException(
                    "coverage units must satisfy 0 < currentUnits <= totalRemainingUnits for group "
                            + groupId + ": current=" + cu + ", total=" + tu);
        }

        BigDecimal release = start.csm().signum() == 0
                ? zero()
                : scale(start.csm().multiply(cu).divide(tu, DecimalScale.SCALE, DecimalScale.ROUNDING));
        List<JournalEntry> entries = new java.util.ArrayList<>();
        if (release.signum() > 0) {
            entries.add(journalFactory.entry(groupId, periodOf(events), "CURRENT_SERVICE_RELEASE",
                    "release-csm",
                    List.of(
                            journalFactory.debit(Account.INSURANCE_REVENUE, release),
                            journalFactory.credit(Account.CSM, release))));
        }
        Balances movement = new Balances(release.negate(), zero(), zero());
        return new StepRoll("CURRENT_SERVICE_RELEASE", start, movement, add(start, movement),
                List.copyOf(entries));
    }

    /** 步骤4：现金流结算，仅资产负债表移动，不影响 CSM 与亏损组成。 */
    public StepRoll cashSettlement(Balances opening, List<MovementEvent> events, String groupId) {
        Balances start = clean(opening);
        Balances current = start;
        List<JournalEntry> entries = new java.util.ArrayList<>();
        BigDecimal fcfMove = zero();

        for (MovementEvent event : events) {
            if (event.type() != MovementType.CASH_SETTLEMENT) {
                continue;
            }
            ruleBook.requireConsistentSign(event.type(), event.direction(), event.amount());
            BigDecimal paid = scale(event.amount());
            if (paid.signum() == 0) {
                continue;
            }
            if (paid.compareTo(current.fulfillmentCashFlows()) > 0) {
                throw new CsmValidationException(
                        "cash settlement " + paid + " exceeds fulfillment cash flows "
                                + current.fulfillmentCashFlows() + " for group " + groupId);
            }
            Balances delta = new Balances(zero(), zero(), paid.negate());
            entries.add(journalFactory.entry(groupId, event.periodIndex(), "CASH_SETTLEMENT",
                    event.description() == null ? "settlement" : event.description(),
                    List.of(
                            journalFactory.debit(Account.FULFILLMENT_CASH_FLOWS, paid),
                            journalFactory.credit(Account.CASH, paid))));
            current = add(current, delta);
            fcfMove = fcfMove.add(delta.fulfillmentCashFlows());
        }

        Balances movement = new Balances(zero(), zero(), scale(fcfMove));
        return new StepRoll("CASH_SETTLEMENT", start, movement, current, List.copyOf(entries));
    }

    private static Balances clean(Balances balances) {
        if (balances == null) {
            throw new CsmValidationException("balances must not be null");
        }
        BigDecimal csm = DecimalScale.normalize(balances.csm());
        BigDecimal loss = DecimalScale.normalize(balances.lossComponent());
        BigDecimal fcf = DecimalScale.normalize(balances.fulfillmentCashFlows());
        if (csm.signum() < 0 || loss.signum() < 0 || fcf.signum() < 0) {
            throw new CsmValidationException("balances must be non-negative: " + balances);
        }
        return new Balances(csm, loss, fcf);
    }

    private static Balances add(Balances a, Balances b) {
        return new Balances(scale(a.csm().add(b.csm())),
                scale(a.lossComponent().add(b.lossComponent())),
                scale(a.fulfillmentCashFlows().add(b.fulfillmentCashFlows())));
    }

    private static BigDecimal scale(BigDecimal value) {
        return DecimalScale.normalize(value);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(DecimalScale.SCALE);
    }

    private static int periodOf(List<MovementEvent> events) {
        return events.isEmpty() ? 0 : events.get(0).periodIndex();
    }

    private static String ref(MovementEvent event, String suffix) {
        return (event.description() == null ? event.type().name() : event.description())
                + ":" + suffix;
    }
}

