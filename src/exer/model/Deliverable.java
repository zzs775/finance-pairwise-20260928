package exer.model;

import java.math.BigInteger;
import java.util.List;

/** 每份合约的交割组合：若干证券腿与若干现金腿（不可变）。 */
public record Deliverable(String contractId, List<SecurityLeg> securityLegs, List<CashLeg> cashLegs) {

    /**
     * 证券腿：每份合约比率为 unscaledPerContract * 10^(-scale) 份证券，
     * 结算精度 settleScale 可粗于比率精度（0 <= settleScale <= scale），
     * 由此产生的舍入尾差由生成器逐账户记录。
     */
    public record SecurityLeg(String symbol, int scale, BigInteger unscaledPerContract, int settleScale) {

        public SecurityLeg {
            if (symbol == null || symbol.isBlank()) {
                throw new IllegalArgumentException("security leg symbol must be non-blank");
            }
            if (scale < 0 || settleScale < 0 || settleScale > scale) {
                throw new IllegalArgumentException("illegal scale on security leg " + symbol);
            }
            if (unscaledPerContract == null || unscaledPerContract.signum() < 0) {
                throw new IllegalArgumentException("illegal ratio on security leg " + symbol);
            }
        }
    }

    /** 现金腿：每份合约比率为 unscaledPerContract * 10^(-scale) 单位货币，语义同证券腿。 */
    public record CashLeg(String currency, int scale, BigInteger unscaledPerContract, int settleScale) {

        public CashLeg {
            if (currency == null || currency.isBlank()) {
                throw new IllegalArgumentException("cash leg currency must be non-blank");
            }
            if (scale < 0 || settleScale < 0 || settleScale > scale) {
                throw new IllegalArgumentException("illegal scale on cash leg " + currency);
            }
            if (unscaledPerContract == null || unscaledPerContract.signum() < 0) {
                throw new IllegalArgumentException("illegal ratio on cash leg " + currency);
            }
        }
    }

    public Deliverable {
        if (contractId == null || contractId.isBlank()) {
            throw new IllegalArgumentException("deliverable contractId must be non-blank");
        }
        securityLegs = securityLegs == null ? List.of() : List.copyOf(securityLegs);
        cashLegs = cashLegs == null ? List.of() : List.copyOf(cashLegs);
        if (securityLegs.isEmpty() && cashLegs.isEmpty()) {
            throw new IllegalArgumentException("deliverable must contain at least one leg");
        }
    }
}
