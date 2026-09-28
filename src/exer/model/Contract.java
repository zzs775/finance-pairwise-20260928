package exer.model;

/** 期权合约（不可变）。 */
public record Contract(String contractId, String underlyingSymbol, long expiryEpochDay) {
}
