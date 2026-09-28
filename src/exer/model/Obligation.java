package exer.model;

import java.math.BigInteger;
import java.util.List;

/** 某账户因某条通知被分配后生成的交割义务（不可变）。 */
public record Obligation(String noticeKey, String accountId, String contractId,
                         BigInteger assignedQuantity,
                         List<SecurityDelivery> securities, List<CashDelivery> cash) {

    public record SecurityDelivery(String symbol, int scale, BigInteger unscaledQuantity) {

        public SecurityDelivery {
            if (unscaledQuantity == null || unscaledQuantity.signum() < 0) {
                throw new IllegalArgumentException("security delivery quantity must be non-negative");
            }
        }
    }

    public record CashDelivery(String currency, int scale, BigInteger unscaledAmount) {

        public CashDelivery {
            if (unscaledAmount == null || unscaledAmount.signum() < 0) {
                throw new IllegalArgumentException("cash delivery amount must be non-negative");
            }
        }
    }

    public Obligation {
        if (noticeKey == null || noticeKey.isBlank()) {
            throw new IllegalArgumentException("obligation noticeKey must be non-blank");
        }
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("obligation accountId must be non-blank");
        }
        if (assignedQuantity == null || assignedQuantity.signum() < 0) {
            throw new IllegalArgumentException("assigned quantity must be non-negative");
        }
        securities = securities == null ? List.of() : List.copyOf(securities);
        cash = cash == null ? List.of() : List.copyOf(cash);
    }
}
