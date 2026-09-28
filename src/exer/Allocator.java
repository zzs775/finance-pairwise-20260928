package exer;

import exer.model.Account;
import exer.model.AllocationPlan;
import exer.model.AccountShare;
import exer.model.EngineException;
import exer.model.ExerciseNotice;
import exer.model.RejectReason;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 比例配额 + 稳定轮转余数分配器。 */
public final class Allocator {

    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;

    /**
     * 按账户可分配空头的比例分配通知数量：
     * baseQuota_i = floor(Q * short_i / totalShort)，余数用稳定轮转逐份发放。
     * 账户按 accountId 字典序排序，因此输入顺序不影响结果。
     *
     * 余数始终从字典序首个仍有空位的账户开始发放：这样通知以任何批次顺序提交、
     * 账户以任何顺序输入，最终分配都相同（重放/恢复逐字节一致所必需）。
     * 游标仍逐份推进，最终位置经 finalCursor 暴露，供差分测试与 oracle 对比。
     *
     * @param startCursor 兼容参数；规范化后不影响确定性结果
     */
    public AllocationPlan allocate(ExerciseNotice notice, List<Account> accounts, int startCursor) {
        BigInteger quantity = notice.quantity();
        if (quantity == null || quantity.signum() <= 0) {
            throw new EngineException(RejectReason.INVALID_NOTICE,
                    "exercise quantity must be positive: " + quantity);
        }

        List<Account> eligible = new ArrayList<>();
        for (Account account : accounts) {
            if (account.shortPosition() == null || account.shortPosition().signum() < 0) {
                throw new EngineException(RejectReason.INVALID_NOTICE,
                        "short position must be non-null and non-negative for " + account.accountId());
            }
            eligible.add(account);
        }
        eligible.sort(Comparator.comparing(Account::accountId));

        BigInteger totalShort = ZERO;
        for (Account account : eligible) {
            totalShort = totalShort.add(account.shortPosition());
        }
        if (totalShort.signum() == 0) {
            throw new EngineException(RejectReason.ZERO_ELIGIBLE_SHORT,
                    "no eligible short position for contract " + notice.contractId());
        }
        if (quantity.compareTo(totalShort) > 0) {
            throw new EngineException(RejectReason.OVER_EXERCISE,
                    "exercise quantity " + quantity + " exceeds eligible short " + totalShort);
        }

        int n = eligible.size();
        BigInteger[] base = new BigInteger[n];
        BigInteger assigned = ZERO;
        for (int i = 0; i < n; i++) {
            base[i] = eligible.get(i).shortPosition().signum() == 0
                    ? ZERO
                    : quantity.multiply(eligible.get(i).shortPosition()).divide(totalShort);
            assigned = assigned.add(base[i]);
        }

        BigInteger[] extra = new BigInteger[n];
        java.util.Arrays.fill(extra, ZERO);
        List<String> cursorTrail = new ArrayList<>();
        BigInteger remainder = quantity.subtract(assigned);
        int cursor = 0;
        for (int i = 0; i < n && remainder.signum() > 0; i++) {
            Account account = eligible.get(i);
            BigInteger used = base[i].add(extra[i]);
            if (used.compareTo(account.shortPosition()) < 0) {
                extra[i] = extra[i].add(ONE);
                cursorTrail.add(account.accountId());
                remainder = remainder.subtract(ONE);
                cursor = (i + 1) % n;
            }
        }

        List<AccountShare> shares = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            shares.add(new AccountShare(eligible.get(i).accountId(), base[i], extra[i],
                    base[i].add(extra[i])));
        }
        return new AllocationPlan(notice.idempotencyKey(), notice.contractId(), quantity,
                List.copyOf(shares), List.copyOf(cursorTrail), cursor);
    }

    public AllocationPlan allocate(ExerciseNotice notice, List<Account> accounts) {
        return allocate(notice, accounts, 0);
    }
}
