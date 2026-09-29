package loanmonitor.model;

/** Logical identity of one covenant-period breach. */
public record BreachKey(String covenantId, String periodLabel) {
}
