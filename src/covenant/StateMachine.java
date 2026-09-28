package covenant;

import java.time.LocalDate;

public final class StateMachine {

    public record Input(boolean breach,
                        boolean withinGrace,
                        boolean waived,
                        LocalDate firstBreachDate,
                        LocalDate asOf) {
    }

    public record Transition(Status from, Status to, AlertType alert) {
    }

    /**
     * Deterministic transition table:
     * <pre>
     *  COMPLIANT   + breach & grace>0  -> CURE_PENDING (CURE_PENDING)
     *  COMPLIANT   + breach & grace==0 -> BREACHED      (BREACH_DETECTED)
     *  COMPLIANT   + no breach         -> COMPLIANT
     *  CURE_PENDING + still breach in grace -> CURE_PENDING
     *  CURE_PENDING + breach after grace    -> DEFAULTED (DEFAULTED)
     *  CURE_PENDING + no breach             -> COMPLIANT (CURED)
     *  BREACHED  + breach + waiver active -> WAIVED (WAIVER_APPLIED)
     *  BREACHED  + breach, no waiver      -> BREACHED
     *  BREACHED  + no breach              -> COMPLIANT (CURED)
     *  WAIVED    + waiver active + breach -> WAIVED
     *  WAIVED    + waiver expired+breach in grace  -> CURE_PENDING (WAIVER_EXPIRED)
     *  WAIVED    + waiver expired+breach after grace -> DEFAULTED (WAIVER_EXPIRED)
     *  WAIVED    + no breach                     -> COMPLIANT (CURED)
     *  DEFAULTED -> terminal
     * </pre>
     */
    public Transition step(Status current, Input input) {
        if (current == Status.DEFAULTED) {
            return new Transition(Status.DEFAULTED, Status.DEFAULTED, null);
        }
        if (!input.breach()) {
            return current == Status.COMPLIANT
                    ? new Transition(current, Status.COMPLIANT, null)
                    : new Transition(current, Status.COMPLIANT, AlertType.CURED);
        }
        if (input.waived()) {
            return current == Status.WAIVED
                    ? new Transition(current, Status.WAIVED, null)
                    : new Transition(current, Status.WAIVED, AlertType.WAIVER_APPLIED);
        }
        if (current == Status.WAIVED) {
            Status next = input.withinGrace() ? Status.CURE_PENDING : Status.DEFAULTED;
            return new Transition(current, next, AlertType.WAIVER_EXPIRED);
        }
        if (input.withinGrace()) {
            return current == Status.CURE_PENDING
                    ? new Transition(current, Status.CURE_PENDING, null)
                    : new Transition(current, Status.CURE_PENDING, AlertType.CURE_PENDING);
        }
        if (current == Status.CURE_PENDING) {
            return new Transition(current, Status.DEFAULTED, AlertType.DEFAULTED);
        }
        if (current == Status.BREACHED) {
            return new Transition(current, Status.BREACHED, null);
        }
        return new Transition(current, Status.BREACHED, AlertType.BREACH_DETECTED);
    }
}
