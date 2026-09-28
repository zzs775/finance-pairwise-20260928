package exer;

import exer.model.Account;
import exer.model.AllocationPlan;
import exer.model.BatchResult;
import exer.model.Contract;
import exer.model.Deliverable;
import exer.model.EngineException;
import exer.model.ExerciseNotice;
import exer.model.GenerationResult;
import exer.model.Obligation;
import exer.model.RejectReason;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** 行权责任分配引擎：幂等、批次暂存、一次提交。 */
public final class ExerciseEngine {

    private final CheckpointStore store;
    private final FailureInjector failureInjector;
    private final Allocator allocator;
    private final ObligationGenerator generator;
    private final long todayEpochDay;

    public ExerciseEngine() {
        this(new CheckpointStore(), new FailureInjector(), LocalDate.now().toEpochDay());
    }

    public ExerciseEngine(CheckpointStore store, FailureInjector failureInjector, long todayEpochDay) {
        this.store = store;
        this.failureInjector = failureInjector;
        this.allocator = new Allocator();
        this.generator = new ObligationGenerator();
        this.todayEpochDay = todayEpochDay;
    }

    public CheckpointStore checkpointStore() {
        return store;
    }

    public FailureInjector failureInjector() {
        return failureInjector;
    }

    public void registerContract(Contract contract, Deliverable deliverable) {
        if (contract == null || deliverable == null) {
            throw new EngineException(RejectReason.ILLEGAL_DELIVERABLE,
                    "contract and deliverable must be non-null");
        }
        if (!contract.contractId().equals(deliverable.contractId())) {
            throw new EngineException(RejectReason.ILLEGAL_DELIVERABLE,
                    "deliverable contract id does not match contract " + contract.contractId());
        }
        CheckpointStore.State state = store.state();
        if (state.contracts().containsKey(contract.contractId())) {
            throw new EngineException(RejectReason.ILLEGAL_DELIVERABLE,
                    "contract already registered: " + contract.contractId());
        }
        TreeMap<String, Contract> contracts = new TreeMap<>(state.contracts());
        contracts.put(contract.contractId(), contract);
        TreeMap<String, Deliverable> deliverables = new TreeMap<>(state.deliverables());
        deliverables.put(contract.contractId(), deliverable);
        store.replaceState(new CheckpointStore.State(contracts, deliverables,
                state.assignedByContract(), state.cursorByContract(), state.committedKeys(),
                state.obligations(), state.batchSeq()));
    }

    /**
     * 处理一个批次。整个批次要么全部成功提交，要么对外状态完全不变。
     * 批次内通知必须针对同一合约；通知按幂等键排序后确定性处理。
     */
    public BatchResult processBatch(List<ExerciseNotice> notices, List<Account> accounts) {
        if (notices == null || notices.isEmpty()) {
            throw new EngineException(RejectReason.INVALID_NOTICE, "batch must contain notices");
        }
        if (accounts == null || accounts.isEmpty()) {
            throw new EngineException(RejectReason.INVALID_NOTICE, "batch must contain accounts");
        }
        List<ExerciseNotice> ordered = new ArrayList<>(notices);
        ordered.sort((a, b) -> a.idempotencyKey().compareTo(b.idempotencyKey()));

        Set<String> batchKeys = new HashSet<>();
        String contractId = null;
        for (ExerciseNotice notice : ordered) {
            if (notice == null || notice.idempotencyKey() == null
                    || notice.idempotencyKey().isBlank()
                    || notice.contractId() == null || notice.contractId().isBlank()) {
                throw new EngineException(RejectReason.INVALID_NOTICE, "malformed notice");
            }
            if (!batchKeys.add(notice.idempotencyKey())) {
                throw new EngineException(RejectReason.DUPLICATE_NOTICE,
                        "duplicate notice inside batch: " + notice.idempotencyKey());
            }
            if (contractId == null) {
                contractId = notice.contractId();
            } else if (!contractId.equals(notice.contractId())) {
                throw new EngineException(RejectReason.INVALID_NOTICE,
                        "batch mixes multiple contracts");
            }
        }

        Set<String> accountIds = new HashSet<>();
        for (Account account : accounts) {
            if (account == null || account.accountId() == null || account.accountId().isBlank()) {
                throw new EngineException(RejectReason.INVALID_NOTICE, "malformed account");
            }
            if (!accountIds.add(account.accountId())) {
                throw new EngineException(RejectReason.INVALID_NOTICE,
                        "duplicate account in batch: " + account.accountId());
            }
        }

        final CheckpointStore.State before = store.state();
        try {
            for (String key : batchKeys) {
                if (before.committedKeys().contains(key)) {
                    throw new EngineException(RejectReason.DUPLICATE_NOTICE,
                            "notice already committed: " + key);
                }
            }
            Contract contract = before.contracts().get(contractId);
            if (contract == null) {
                throw new EngineException(RejectReason.UNKNOWN_CONTRACT,
                        "unknown contract: " + contractId);
            }
            if (contract.expiryEpochDay() < todayEpochDay) {
                throw new EngineException(RejectReason.EXPIRED_CONTRACT,
                        "contract expired: " + contractId);
            }
            Deliverable deliverable = before.deliverables().get(contractId);

            Map<String, BigInteger> cumulative =
                    new TreeMap<>(before.assignedByContract()
                            .getOrDefault(contractId, Map.of()));
            int cursor = before.cursorByContract().getOrDefault(contractId, 0);
            List<Obligation> stagedObligations = new ArrayList<>(before.obligations());

            for (ExerciseNotice notice : ordered) {
                if (notice.quantity() == null || notice.quantity().signum() <= 0) {
                    throw new EngineException(RejectReason.INVALID_NOTICE,
                            "exercise quantity must be positive in " + notice.idempotencyKey());
                }
                List<Account> remaining = new ArrayList<>();
                BigInteger totalRemaining = BigInteger.ZERO;
                for (Account account : accounts) {
                    BigInteger used = cumulative.getOrDefault(account.accountId(), BigInteger.ZERO);
                    if (used.compareTo(account.shortPosition()) > 0) {
                        throw new EngineException(RejectReason.OVER_EXERCISE,
                                "already assigned exceeds short for " + account.accountId());
                    }
                    BigInteger available = account.shortPosition().subtract(used);
                    remaining.add(new Account(account.accountId(), available));
                    totalRemaining = totalRemaining.add(available);
                }
                if (notice.quantity().compareTo(totalRemaining) > 0) {
                    throw new EngineException(RejectReason.OVER_EXERCISE,
                            "notice " + notice.idempotencyKey() + " quantity " + notice.quantity()
                                    + " exceeds remaining short " + totalRemaining);
                }
                failureInjector.failIfTriggered(FailurePoint.ALLOCATE);
                AllocationPlan plan = allocator.allocate(notice, remaining, cursor);
                cursor = plan.finalCursor();

                for (var share : plan.shares()) {
                    cumulative.merge(share.accountId(), share.quantity(), BigInteger::add);
                }

                failureInjector.failIfTriggered(FailurePoint.GENERATE_OBLIGATIONS);
                GenerationResult result = generator.generate(deliverable, plan);
                stagedObligations.addAll(result.obligations());
            }

            failureInjector.failIfTriggered(FailurePoint.WRITE_LOG);

            TreeSet<String> committedKeys = new TreeSet<>(before.committedKeys());
            committedKeys.addAll(batchKeys);
            TreeMap<String, Map<String, BigInteger>> assignedByContract =
                    new TreeMap<>(before.assignedByContract());
            assignedByContract.put(contractId, cumulative);
            TreeMap<String, Integer> cursorByContract =
                    new TreeMap<>(before.cursorByContract());
            cursorByContract.put(contractId, cursor);
            CheckpointStore.State after = new CheckpointStore.State(
                    before.contracts(), before.deliverables(), assignedByContract,
                    cursorByContract, committedKeys, stagedObligations, before.batchSeq() + 1);
            store.replaceState(after);
            return new BatchResult("batch-" + before.batchSeq(),
                    List.copyOf(stagedObligations.subList(
                            before.obligations().size(), stagedObligations.size())));
        } catch (EngineException exception) {
            store.replaceState(before);
            throw exception;
        } catch (RuntimeException exception) {
            store.replaceState(before);
            throw new EngineException(RejectReason.INVALID_NOTICE, exception.getMessage());
        }
    }
}
