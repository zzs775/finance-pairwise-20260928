package exer;

import exer.model.Account;
import exer.model.AccountShare;
import exer.model.AllocationPlan;
import exer.model.Deliverable;
import exer.model.ExerciseNotice;
import exer.model.GenerationResult;
import exer.model.Obligation;
import exer.model.TailLedgerEntry;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 朴素 oracle：按合约逐份展开。
 * 分配时先逐份补齐每个账户的比例基础配额（按账户顺序），再从游标起逐份轮转余数；
 * 交割时每份合约逐条腿累加。用于小批次与引擎结果做差分对比。
 */
public final class NaiveOracle {

    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;
    private static final BigInteger TEN = BigInteger.TEN;

    public AllocationPlan allocate(ExerciseNotice notice, List<Account> accounts, int startCursor) {
        List<Account> eligible = accounts.stream()
                .filter(a -> a.shortPosition().signum() > 0)
                .sorted(Comparator.comparing(Account::accountId))
                .toList();
        int n = eligible.size();
        BigInteger totalShort = ZERO;
        for (Account account : eligible) {
            totalShort = totalShort.add(account.shortPosition());
        }
        BigInteger quantity = notice.quantity();

        BigInteger[] base = new BigInteger[n];
        BigInteger assigned = ZERO;
        for (int i = 0; i < n; i++) {
            base[i] = quantity.multiply(eligible.get(i).shortPosition()).divide(totalShort);
            assigned = assigned.add(base[i]);
        }

        BigInteger[] perUnit = new BigInteger[n];
        List<String> unitTrail = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (BigInteger k = ZERO; k.compareTo(base[i]) < 0; k = k.add(ONE)) {
                perUnit[i] = perUnit[i] == null ? ONE : perUnit[i].add(ONE);
                unitTrail.add(eligible.get(i).accountId());
            }
        }

        List<String> remainderTrail = new ArrayList<>();
        BigInteger remainder = quantity.subtract(assigned);
        int cursor = 0;
        for (int i = 0; i < n && remainder.signum() > 0; i++) {
            Account account = eligible.get(i);
            BigInteger used = perUnit[i] == null ? ZERO : perUnit[i];
            if (used.compareTo(account.shortPosition()) < 0) {
                perUnit[i] = used.add(ONE);
                remainderTrail.add(account.accountId());
                unitTrail.add(account.accountId());
                remainder = remainder.subtract(ONE);
                cursor = (i + 1) % n;
            }
        }

        List<AccountShare> shares = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BigInteger quota = perUnit[i] == null ? ZERO : perUnit[i];
            shares.add(new AccountShare(eligible.get(i).accountId(), base[i],
                    quota.subtract(base[i]), quota));
        }
        return new AllocationPlan(notice.idempotencyKey(), notice.contractId(), quantity, List.copyOf(shares),
                List.copyOf(remainderTrail), cursor);
    }

    public AllocationPlan allocate(ExerciseNotice notice, List<Account> accounts) {
        return allocate(notice, accounts, 0);
    }

    /** 逐份合约展开交割：先算出每份的腿增量，再汇总到账户。 */
    public GenerationResult generate(Deliverable deliverable, AllocationPlan plan) {
        List<Obligation> obligations = new ArrayList<>();
        List<TailLedgerEntry> tailLedger = new ArrayList<>();
        for (AccountShare share : plan.shares()) {
            List<Obligation.SecurityDelivery> securities = new ArrayList<>();
            for (Deliverable.SecurityLeg leg : deliverable.securityLegs()) {
                BigInteger exact = ZERO;
                for (BigInteger unit = ZERO; unit.compareTo(share.quantity()) < 0;
                     unit = unit.add(ONE)) {
                    exact = exact.add(leg.unscaledPerContract());
                }
                BigInteger factor = TEN.pow(leg.scale() - leg.settleScale());
                BigInteger delivered = exact.divide(factor);
                BigInteger tail = exact.subtract(delivered.multiply(factor));
                securities.add(new Obligation.SecurityDelivery(leg.symbol(),
                        leg.settleScale(), delivered));
                if (tail.signum() != 0 || factor.compareTo(ONE) > 0) {
                    tailLedger.add(new TailLedgerEntry(share.accountId(), "SECURITY", leg.symbol(),
                            leg.scale(), leg.settleScale(), exact, delivered, tail));
                }
            }
            List<Obligation.CashDelivery> cash = new ArrayList<>();
            for (Deliverable.CashLeg leg : deliverable.cashLegs()) {
                BigInteger exact = ZERO;
                for (BigInteger unit = ZERO; unit.compareTo(share.quantity()) < 0;
                     unit = unit.add(ONE)) {
                    exact = exact.add(leg.unscaledPerContract());
                }
                BigInteger factor = TEN.pow(leg.scale() - leg.settleScale());
                BigInteger delivered = exact.divide(factor);
                BigInteger tail = exact.subtract(delivered.multiply(factor));
                cash.add(new Obligation.CashDelivery(leg.currency(), leg.settleScale(), delivered));
                if (tail.signum() != 0 || factor.compareTo(ONE) > 0) {
                    tailLedger.add(new TailLedgerEntry(share.accountId(), "CASH", leg.currency(),
                            leg.scale(), leg.settleScale(), exact, delivered, tail));
                }
            }
            obligations.add(new Obligation(plan.noticeKey(), share.accountId(), plan.contractId(),
                    share.quantity(), securities, cash));
        }
        return new GenerationResult(obligations, tailLedger);
    }
}
