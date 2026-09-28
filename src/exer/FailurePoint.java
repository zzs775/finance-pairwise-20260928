package exer;

/** 可注入故障的位置。 */
public enum FailurePoint {
    ALLOCATE,
    GENERATE_OBLIGATIONS,
    WRITE_LOG
}
