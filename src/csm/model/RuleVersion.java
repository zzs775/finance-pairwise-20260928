package csm.model;

/** 题内冻结规则版本。事件按其发生时戳记的版本处理，不随"最新版本"漂移。 */
public enum RuleVersion {

    RULES_V1("V1"),
    RULES_V2("V2");

    private final String code;

    RuleVersion(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static RuleVersion fromCode(String code) {
        for (RuleVersion version : values()) {
            if (version.code.equals(code)) {
                return version;
            }
        }
        throw new csm.model.ValidationException("unknown rule version: " + code);
    }
}
