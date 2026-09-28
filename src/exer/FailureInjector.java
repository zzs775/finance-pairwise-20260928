package exer;

import exer.model.EngineException;
import exer.model.RejectReason;

/** 故障注入器：在指定点抛出异常，用于验证暂存回滚。 */
public final class FailureInjector {

    private FailurePoint armed;

    public synchronized void arm(FailurePoint point) {
        this.armed = point;
    }

    public synchronized void disarm() {
        this.armed = null;
    }

    /** 在指定点若已武装则抛出异常；无论是否触发，故障都在触发后自动解除（一次性）。 */
    public synchronized void failIfTriggered(FailurePoint point) {
        if (armed == point) {
            armed = null;
            throw new EngineException(RejectReason.INJECTED_FAILURE,
                    "injected failure at " + point);
        }
    }
}
