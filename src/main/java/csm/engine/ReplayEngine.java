package csm.engine;

import csm.model.ContractGroup;
import csm.model.GroupRoll;
import csm.model.RuleVersion;

import java.util.Map;
import java.util.List;

/**
 * 重放引擎：规则换版只影响生效后的事件，历史期间按事件自带版本冻结重放。
 */
public final class ReplayEngine {

    private final CsmRollEngine engine;
    private final Map<Integer, RuleVersion> versionTimeline;

    public ReplayEngine() {
        this(Map.of());
    }

    /**
     * @param versionTimeline 期间索引 -> 该期间生效的冻结版本；事件自带版本优先且不得与之冲突。
     *                        历史期间可按原版本重放，新版本只出现在换版生效后的期间。
     */
    public ReplayEngine(Map<Integer, RuleVersion> versionTimeline) {
        this.engine = new CsmRollEngine();
        this.versionTimeline = Map.copyOf(versionTimeline);
    }

    public List<GroupRoll> replay(ContractGroup group, int periodCount) {
        for (Map.Entry<Integer, RuleVersion> entry : versionTimeline.entrySet()) {
            if (entry.getKey() < 0 || entry.getValue() == null) {
                throw new CsmValidationException("invalid version timeline entry: " + entry);
            }
        }
        java.util.List<csm.model.MovementEvent> events =
                new java.util.ArrayList<>(group.events());
        for (int p = 0; p < periodCount; p++) {
            RuleVersion timelineVersion = versionTimeline.get(p);
            if (timelineVersion == null) {
                continue;
            }
            for (int i = 0; i < events.size(); i++) {
                csm.model.MovementEvent event = events.get(i);
                if (event.periodIndex() == p && event.ruleVersion() != timelineVersion) {
                    throw new CsmValidationException(
                            "frozen event version " + event.ruleVersion()
                                    + " conflicts with timeline version " + timelineVersion
                                    + " at period " + p + " (history must replay under old version)");
                }
            }
        }
        return engine.rollAllPeriods(group, periodCount);
    }
}
