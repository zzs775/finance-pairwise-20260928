package csm.engine;

/**
 * 输入校验失败（未知规则版本、符号冲突、覆盖单位非法等）时抛出。
 */
public class CsmValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CsmValidationException(String message) {
        super(message);
    }
}
