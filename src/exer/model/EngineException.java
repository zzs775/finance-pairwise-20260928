package exer.model;

/** 引擎对外抛出的分类异常。 */
public class EngineException extends RuntimeException {

    private final RejectReason reason;

    public EngineException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason reason() {
        return reason;
    }
}
