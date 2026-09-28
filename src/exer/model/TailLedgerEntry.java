package exer.model;

import java.math.BigInteger;

/**
 * 尾差台账条目：某账户某腿按比率精算得到 exactUnscaled（在比率精度 scale 下），
 * 实际按 settleScale 结算 deliveredUnscaled，尾差 remainderUnscaled = exact - delivered*10^(scale-settleScale)。
 */
public record TailLedgerEntry(String accountId, String kind, String legId, int scale, int settleScale,
                              BigInteger exactUnscaled, BigInteger deliveredUnscaled,
                              BigInteger remainderUnscaled) {
}
