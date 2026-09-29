package csm.engine;

import csm.model.GroupRollforward;
import csm.model.OpeningBalances;
import csm.model.PeriodInput;
import csm.model.StepResult;
import csm.model.ValidationException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 多期重放：历史期间按各自戳记的规则版本处理，期末结转为下期期初。 */
public final class ReplayEngine {

    private final CsmEngine engine;
    private final MechanicalOracle oracle;

    public ReplayEngine() {
        this(new CsmEngine(), new MechanicalOracle());
    }

    public ReplayEngine(CsmEngine engine, MechanicalOracle oracle) {
        this.engine = engine;
        this.oracle = oracle;
    }

    public List<GroupRollforward> replay(List<PeriodInput> periods) {
        if (periods.isEmpty()) {
            throw new ValidationException("no periods to replay");
        }

        Map<String, List<PeriodInput>> byGroup = periods.stream()
                .collect(Collectors.groupingBy(PeriodInput::groupId));

        List<GroupRollforward> output = new ArrayList<>();
        for (Map.Entry<String, List<PeriodInput>> entry : byGroup.entrySet()) {
            List<PeriodInput> ordered = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(PeriodInput::sequence))
                    .toList();
            replayOneGroup(ordered, output);
        }
        output.sort(Comparator
                .comparing(GroupRollforward::groupId)
                .thenComparing(result -> result.input().sequence()));
        return List.copyOf(output);
    }

    private void replayOneGroup(List<PeriodInput> ordered,
                                List<GroupRollforward> output) {
        OpeningBalances carried = ordered.getFirst().opening();
        boolean first = true;

        for (PeriodInput original : ordered) {
            PeriodInput effective = original;
            if (!first) {
                effective = new PeriodInput(
                        original.groupId(),
                        original.periodId(),
                        original.sequence(),
                        original.ruleVersionCode(),
                        carried,
                        original.currentRate(),
                        original.lockedRate(),
                        original.futureServiceChange(),
                        original.currentCoverageUnits(),
                        original.remainingCoverageUnits(),
                        original.settlement());
            }

            GroupRollforward result = engine.run(effective);
            oracle.verify(result);
            output.add(result);

            carried = new OpeningBalances(
                    result.closingFcf(),
                    result.closingCsm(),
                    result.closingLossComponent());
            first = false;
        }
    }
}
