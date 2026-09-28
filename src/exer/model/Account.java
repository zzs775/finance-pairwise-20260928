package exer.model;

import java.math.BigInteger;

/** 账户及其在目标合约上的可分配空头数量（不可变）。 */
public record Account(String accountId, BigInteger shortPosition) {
}
