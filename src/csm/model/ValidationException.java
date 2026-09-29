package csm.model;

/** 输入不合法：未知规则版本、符号冲突等，引擎必须拒绝处理。 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }
}
