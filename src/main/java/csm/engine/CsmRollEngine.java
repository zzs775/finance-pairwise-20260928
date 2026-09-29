package csm.engine;

import csm.model.ContractGroup;
import csm.model.GroupRoll;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import csm.model.Balances;
import csm.model.CoveragePeriodUnits;
import csm.model.JournalEntry;
import csm.model.MovementEvent;
import csm.model.RuleVersion;
import csm.model.StepRoll;
import csm.util.DecimalScale;

/**
 * 单组单期间 CSM 滚动引擎，严格按题内四步顺序处理，每步保留期初/变动/期末。
 */
public final class CsmRollEngine {

    public GroupRoll rollOnePeriod(ContractGroup group, int periodIndex) {
        return rollOnePeriod(group, periodIndex, null);
    }

    /**
     * @param openingOverride 重放时上期期末余额；为 null 时使用组期初余额（仅 periodIndex=0 合法）
     */
    public GroupRoll rollOnePeriod(ContractGroup group, int periodIndex, Balances openingOverride) {
        if (group == null) {
            throw new CsmValidationException("contract group must not be null");
        }
        if (periodIndex < 0) {
            throw new CsmValidationException("period index must be non-negative: " + periodIndex);
        }
        Balances opening = openingOverride == null
                ? normalizeOpening(group)
                : normalizeBalances(openingOverride);

        List<MovementEvent> periodEvents = new ArrayList<>();
        for (MovementEvent event : group.events()) {
            if (event == null) {
                throw new CsmValidationException("null event in group " + group.groupId());
            }
            if (event.periodIndex() == periodIndex) {
                if (event.ruleVersion() == null) {
                    throw new CsmValidationException(
                            "unknown rule version: null at period " + periodIndex
                                    + " in group " + group.groupId());
                }
                periodEvents.add(event);
            } else if (event.periodIndex() < 0) {
                throw new CsmValidationException(
                        "event period index must be non-negative: " + event);
            }
        }

        RuleVersion periodVersion = resolveVersion(periodEvents);
        RuleBook ruleBook = RuleBook.forVersion(periodVersion);
        StepProcessor processor = new StepProcessor(ruleBook);

        CoveragePeriodUnits coverage = findCoverage(group, periodIndex);

        List<StepRoll> steps = new ArrayList<>();
        Balances running = opening;
        running = runStep(steps, processor.interestAccretion(running, periodEvents, group.groupId()));
        running = runStep(steps, processor.futureServiceChange(running, periodEvents, group.groupId()));
        running = runStep(steps, processor.currentServiceRelease(running, periodEvents,
                coverage.currentPeriodUnits(), coverage.totalRemainingUnits(), group.groupId()));
        running = runStep(steps, processor.cashSettlement(running, periodEvents, group.groupId()));

        List<JournalEntry> entries = new ArrayList<>();
        for (StepRoll step : steps) {
            entries.addAll(step.entries());
        }

        GroupRoll roll = new GroupRoll(group.groupId(), periodIndex, periodVersion, opening,
                List.copyOf(steps), running, List.copyOf(entries));
        new RollOracle().verify(roll);
        return roll;
    }

    public List<GroupRoll> rollAllPeriods(ContractGroup group, int periodCount) {
        if (periodCount <= 0) {
            throw new CsmValidationException("period count must be positive: " + periodCount);
        }
        List<GroupRoll> rolls = new ArrayList<>();
        Balances carried = null;
        for (int p = 0; p < periodCount; p++) {
            GroupRoll roll = rollOnePeriod(group, p, carried);
            rolls.add(roll);
            carried = roll.closing();
        }
        new RollOracle().verifyAll(rolls);
        return List.copyOf(rolls);
    }

    private static Balances runStep(List<StepRoll> steps, StepRoll step) {
        steps.add(step);
        return step.closing();
    }

    private static RuleVersion resolveVersion(List<MovementEvent> periodEvents) {
        RuleVersion version = null;
        for (MovementEvent event : periodEvents) {
            if (version == null) {
                version = event.ruleVersion();
            } else if (version != event.ruleVersion()) {
                throw new CsmValidationException(
                        "conflicting rule versions within one period: " + version + " vs "
                                + event.ruleVersion() + " at period " + event.periodIndex());
            }
        }
        return version == null ? RuleVersion.V1 : version;
    }

    private static CoveragePeriodUnits findCoverage(ContractGroup group, int periodIndex) {
        CoveragePeriodUnits found = null;
        for (CoveragePeriodUnits units : group.coverage()) {
            if (units.periodIndex() == periodIndex) {
                if (found != null) {
                    throw new CsmValidationException(
                            "duplicate coverage units at period " + periodIndex
                                    + " in group " + group.groupId());
                }
                found = units;
            }
        }
        if (found == null) {
            throw new CsmValidationException(
                    "missing coverage units at period " + periodIndex
                            + " in group " + group.groupId());
        }
        return found;
    }

    private static Balances normalizeOpening(ContractGroup group) {
        if (group.opening() == null) {
            throw new CsmValidationException(
                    "opening balances missing for group " + group.groupId());
        }
        return normalizeBalances(group.opening());
    }

    private static Balances normalizeBalances(Balances balances) {
        BigDecimal csm = DecimalScale.normalize(balances.csm());
        BigDecimal loss = DecimalScale.normalize(balances.lossComponent());
        BigDecimal fcf = DecimalScale.normalize(balances.fulfillmentCashFlows());
        if (csm.signum() < 0 || loss.signum() < 0 || fcf.signum() < 0) {
            throw new CsmValidationException("balances must be non-negative: " + balances);
        }
        if (csm.signum() > 0 && loss.signum() > 0) {
            throw new CsmValidationException(
                    "CSM and loss component cannot both be positive at opening: " + balances);
        }
        return new Balances(csm, loss, fcf);
    }
}
