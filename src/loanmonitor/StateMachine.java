package loanmonitor;

import java.time.LocalDate;

import loanmonitor.model.BreachKey;
import loanmonitor.model.Status;
import loanmonitor.model.Waiver;

/**
 * Pure state machine for one (covenant, period) pair. All dates are supplied
 * by an injected logical clock; the machine never reads the system clock.
 *
 * Transitions:
 * COMPLIANT -> BREACHED on a failed certification test (instantaneous state
 *   observed exactly on the breach date);
 * BREACHED -> CURE_PENDING on the first subsequent day within grace;
 * CURE_PENDING -> COMPLIANT on cure strictly within the grace window;
 * CURE_PENDING -> DEFAULTED on the day after the grace deadline if uncured;
 * BREACHED/CURE_PENDING -> WAIVED when an active waiver covers this exact
 *   (covenant, period); WAIVED is terminal for the period while the original
 *   breach evidence remains retained by the engine.
 */
public final class StateMachine {

    public record Transition(Status from, Status to, String reason) {
    }

    public StateMachine() {
    }

    public Status initialStatus() {
        return Status.COMPLIANT;
    }

    /** Grace deadline is breachDate + graceDays, inclusive. */
    public LocalDate graceDeadline(LocalDate breachDate, int graceDays) {
        if (graceDays < 0) {
            throw new CovenantException(CovenantException.Code.INVALID_PERIOD,
                    "negative grace period");
        }
        return breachDate.plusDays(graceDays);
    }

    public Transition onTestFailure(BreachKey key, Status current,
                                    LocalDate logicalDate, Waiver waiver) {
        if (current == Status.WAIVED || current == Status.DEFAULTED) {
            return new Transition(current, current, "terminal status retained");
        }
        if (covers(waiver, key, logicalDate)) {
            return new Transition(current, Status.WAIVED,
                    "active waiver " + waiver.id() + " covers breach");
        }
        if (current == Status.BREACHED || current == Status.CURE_PENDING) {
            return new Transition(current, current, "breach already open");
        }
        return new Transition(Status.COMPLIANT, Status.BREACHED,
                "test failed on certification");
    }

    public Transition onTestPass(BreachKey key, Status current,
                                 LocalDate logicalDate, int graceDays,
                                 LocalDate breachDate) {
        if (current == Status.CURE_PENDING || current == Status.BREACHED) {
            LocalDate deadline = graceDeadline(breachDate, graceDays);
            if (logicalDate.isAfter(deadline)) {
                return new Transition(current, Status.DEFAULTED,
                        "cure arrived after grace deadline " + deadline);
            }
            return new Transition(current, Status.COMPLIANT,
                    "cured within grace ending " + deadline);
        }
        if (current == Status.COMPLIANT) {
            return new Transition(current, current, "test passes");
        }
        return new Transition(current, current, "terminal status retained");
    }

    public Transition onDayAdvanced(BreachKey key, Status current,
                                    LocalDate logicalDate, int graceDays,
                                    LocalDate breachDate, boolean stillBreached,
                                    Waiver waiver) {
        if (covers(waiver, key, logicalDate)
                && (current == Status.BREACHED || current == Status.CURE_PENDING)) {
            return new Transition(current, Status.WAIVED,
                    "active waiver " + waiver.id() + " covers open breach");
        }
        if (current == Status.BREACHED) {
            if (!logicalDate.isAfter(breachDate)) {
                return new Transition(current, current, "breach date");
            }
            return new Transition(Status.BREACHED, Status.CURE_PENDING,
                    "grace window opened the day after breach");
        }
        if (current == Status.CURE_PENDING && stillBreached) {
            LocalDate deadline = graceDeadline(breachDate, graceDays);
            if (logicalDate.isAfter(deadline)) {
                return new Transition(Status.CURE_PENDING, Status.DEFAULTED,
                        "grace expired after " + deadline + " without cure");
            }
        }
        return new Transition(current, current, "no status change");
    }

    private static boolean covers(Waiver waiver, BreachKey key,
                                  LocalDate logicalDate) {
        return waiver != null
                && waiver.covenantId().equals(key.covenantId())
                && waiver.periodLabels().contains(key.periodLabel())
                && !logicalDate.isBefore(waiver.effectiveDate())
                && !logicalDate.isAfter(waiver.expiryDate());
    }
}
