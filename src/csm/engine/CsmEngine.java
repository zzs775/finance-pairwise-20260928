package csm.engine;

import csm.model.GroupRollforward;
import csm.model.OpeningBalances;
import csm.model.PeriodInput;
import csm.model.RuleVersion;
import csm.model.StepResult;

import java.util.ArrayList;
import java.util.List;

/** 编排器：按题内固定顺序串联四个步骤，每步独立保留期初/变动/期末。 */
public final class CsmEngine {

    private final FrozenRules rules;
    private final InputValidator validator;
    private final Journal journal;

    public CsmEngine() {
        this(new FrozenRules());
    }

    public CsmEngine(FrozenRules rules) {
        this.rules = rules;
        this.validator = new InputValidator(rules);
        this.journal = new Journal();
    }

    public GroupRollforward run(PeriodInput input) {
        validator.validate(input);
        RuleVersion version = rules.resolve(input.ruleVersionCode());

        var accretion = new StepAccretion(rules, journal);
        var futureService = new StepFutureService(journal);
        var serviceRelease = new StepServiceRelease(journal);
        var settlement = new StepSettlement(journal);

        List<StepResult> steps = new ArrayList<>();

        OpeningBalances current = input.opening();
        StepResult step1 = accretion.apply(input, current);
        steps.add(step1);
        current = closingOf(step1);

        StepResult step2 = futureService.apply(input, current);
        steps.add(step2);
        current = closingOf(step2);

        StepResult step3 = serviceRelease.apply(input, current);
        steps.add(step3);
        current = closingOf(step3);

        StepResult step4 = settlement.apply(input, current);
        steps.add(step4);

        return new GroupRollforward(input.groupId(), input.periodId(),
                version, input, List.copyOf(steps));
    }

    private static OpeningBalances closingOf(StepResult step) {
        return new OpeningBalances(
                step.fulfillmentCashFlow().closing(),
                step.csm().closing(),
                step.lossComponent().closing());
    }
}
