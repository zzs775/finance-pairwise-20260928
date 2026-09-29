package csm.model;

/** 题内固定处理顺序，禁止乱序或用净额跳过中间状态。 */
public enum StepType {
    INTEREST_ACCRETION,
    FUTURE_SERVICE_CHANGE,
    SERVICE_RELEASE,
    SETTLEMENT
}
