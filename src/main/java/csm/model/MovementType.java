package csm.model;

/**
 * 业务变动类型，按题内处理顺序排列。
 */
public enum MovementType {
    INTEREST_ACCRETION,
    FUTURE_SERVICE_CHANGE,
    RISK_ADJUSTMENT_CHANGE,
    CURRENT_SERVICE_RELEASE,
    CASH_SETTLEMENT
}
