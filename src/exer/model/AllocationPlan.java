package exer.model;

import java.math.BigInteger;
import java.util.List;

/** 一次行权通知的完整分配方案（不可变），cursorTrail 记录余数轮转轨迹。 */
public record AllocationPlan(String noticeKey, String contractId, BigInteger totalQuantity,
                             List<AccountShare> shares, List<String> cursorTrail, int finalCursor) {
}
