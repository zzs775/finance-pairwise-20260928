package exer.model;

import java.math.BigInteger;

/** 单账户分配明细：基础配额、余数轮转所得与最终数量（不可变）。 */
public record AccountShare(String accountId, BigInteger baseQuota, BigInteger remainderShare,
                           BigInteger quantity) {
}
