package csm.engine;

import csm.model.Amount;
import csm.model.ClassificationException;
import csm.model.OpeningBalances;
import csm.model.PeriodInput;
import csm.model.RuleVersion;
import csm.model.ValidationException;

/** 统一入口校验：未知版本拒绝、符号冲突拒绝、尺度统一检查。 */
public final class InputValidator {

    private final FrozenRules rules;

    public InputValidator(FrozenRules rules) {
        this.rules = rules;
    }

    public void validate(PeriodInput input) {
        requireText(input.groupId(), "groupId");
        requireText(input.periodId(), "periodId");
        if (input.sequence() < 0) {
            throw new ValidationException("sequence must be non-negative");
        }

        RuleVersion version;
        try {
            version = rules.resolve(input.ruleVersionCode());
        } catch (ValidationException ex) {
            throw ex;
        }

        OpeningBalances opening = input.opening();
        if (opening == null) {
            throw new ValidationException("opening balances required");
        }
        requireNonNegative(opening.fulfillmentCashFlow(), "opening.fcf");
        requireNonNegative(opening.csm(), "opening.csm");
        requireNonNegative(opening.lossComponent(), "opening.lossComponent");

        requireNonNegative(input.currentRate(), "currentRate");
        requireNonNegative(input.lockedRate(), "lockedRate");
        requireNonNegative(input.settlement(), "settlement");

        if (input.futureServiceChange() == null) {
            throw new ValidationException("futureServiceChange required");
        }

        if (input.currentCoverageUnits() == null
                || input.remainingCoverageUnits() == null) {
            throw new ValidationException("coverage units required");
        }
        if (!input.currentCoverageUnits().isPositive()) {
            throw new ClassificationException(
                    "current coverage units must be strictly positive: "
                            + input.currentCoverageUnits());
        }
        if (!input.remainingCoverageUnits().isPositive()) {
            throw new ClassificationException(
                    "remaining coverage units must be strictly positive: "
                            + input.remainingCoverageUnits());
        }
        if (input.currentCoverageUnits()
                .subtract(input.remainingCoverageUnits())
                .isPositive()) {
            throw new ClassificationException(
                    "current coverage units cannot exceed remaining units");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(field + " must be non-blank");
        }
    }

    private static void requireNonNegative(Amount value, String field) {
        if (value == null) {
            throw new ValidationException(field + " required");
        }
        if (value.isNegative()) {
            throw new ValidationException(
                    field + " has conflicting sign, expected non-negative: "
                            + value);
        }
    }
}
