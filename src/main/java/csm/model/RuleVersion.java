package csm.model;

/**
 * 题内冻结规则版本。历史事件按其发生时的版本冻结重放，新版本只影响生效后的事件。
 */
public enum RuleVersion {
    V1,
    V2
}
