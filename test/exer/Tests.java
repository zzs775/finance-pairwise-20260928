package exer;

import exer.model.Account;
import exer.model.AccountShare;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** 纯 JDK 自动化测试入口。运行：java -cp out exer.Tests */
public final class Tests {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<>();
    private static final BigInteger ZERO = BigInteger.ZERO;

    private static final long TODAY = 20000L;
    private static final long FUTURE = TODAY + 30L;
    private static final long PAST = TODAY - 1L;

    public static void main(String[] args) {
        testProportionalQuotaWithRemainder();
        testAllocatedEqualsNoticeAndNotOverShort();
        testInputOrderDoesNotChangeResult();
        testZeroEligibleShortRejected();
        testAllShortRejectsZeroShortOnly();
        testCashDeliveryTotals();
        testPhysicalDeliveryTotals();
        testCompoundDeliveryTotals();
        testTailLedgerTracked();
        testDuplicateNoticeInSameBatchRejected();
        testDuplicateNoticeAcrossBatchesRejected();
        testOverExerciseRejected();
        testCumulativeAcrossBatchesOverExercise();
        testExpiredContractRejected();
        testUnknownContractRejected();
        testIllegalDeliverableRejected();
        testCursorContinuesAcrossNotices();
        testOracleMatchesEngine();
        testInjectedFailureAtAllocateRollsBack();
        testInjectedFailureAtGenerationRollsBack();
        testInjectedFailureAtWriteLogRollsBack();
        testFailureThenRetrySucceeds();
        testReplayIsByteIdentical();
        testSnapshotRestoreIsByteIdentical();

        System.out.println("PASS " + passed + " / " + (passed + failed));
        if (failed > 0) {
            for (String failure : failures) {
                System.out.println("FAIL: " + failure);
            }
            System.exit(1);
        }
    }

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
        } else {
            failed++;
            failures.add(name + (detail == null ? "" : " -> " + detail));
        }
    }

    private static void check(String name, boolean condition) {
        check(name, condition, null);
    }

    private static void expectReject(String name, RejectReason expected, Runnable runnable) {
        try {
            runnable.run();
            check(name, false, "no exception thrown");
        } catch (EngineException exception) {
            check(name, exception.reason() == expected,
                    "expected " + expected + " but got " + exception.reason());
        }
    }

    private static Account account(String id, long shortPosition) {
        return new Account(id, BigInteger.valueOf(shortPosition));
    }

    private static ExerciseNotice notice(String key, String contractId, long quantity) {
        return new ExerciseNotice(key, contractId, BigInteger.valueOf(quantity));
    }

    private static Contract futureContract(String id) {
        return new Contract(id, "FICT-" + id, FUTURE);
    }

    private static Deliverable cashOnly(String contractId) {
        return new Deliverable(contractId, List.of(),
                List.of(new Deliverable.CashLeg("CNX", 2, BigInteger.valueOf(12345), 2)));
    }

    private static Deliverable physicalOnly(String contractId) {
        return new Deliverable(contractId,
                List.of(new Deliverable.SecurityLeg("FICT-STK", 0, BigInteger.ONE, 0)),
                List.of());
    }

    private static Deliverable compound(String contractId) {
        return new Deliverable(contractId,
                List.of(new Deliverable.SecurityLeg("FICT-STK", 0, BigInteger.ONE, 0)),
                List.of(new Deliverable.CashLeg("CNX", 2, BigInteger.valueOf(12345), 2)));
    }

    private static Map<String, BigInteger> quantities(AllocationPlan plan) {
        Map<String, BigInteger> result = new TreeMap<>();
        for (AccountShare share : plan.shares()) {
            result.put(share.accountId(), share.quantity());
        }
        return result;
    }

    private static void testProportionalQuotaWithRemainder() {
        Allocator allocator = new Allocator();
        List<Account> accounts = List.of(account("A", 7), account("B", 1), account("C", 0));
        AllocationPlan plan = allocator.allocate(notice("n1", "C1", 4), accounts);
        Map<String, BigInteger> q = quantities(plan);
        check("remainder: A=4 B=0", q.get("A").equals(BigInteger.valueOf(4))
                && q.get("B").equals(ZERO), q.toString());
        check("remainder trail single A", plan.cursorTrail().equals(List.of("A")),
                plan.cursorTrail().toString());
        check("remainder: sum equals notice",
                q.values().stream().reduce(ZERO, BigInteger::add).equals(BigInteger.valueOf(4)));
    }

    private static void testAllocatedEqualsNoticeAndNotOverShort() {
        Allocator allocator = new Allocator();
        List<Account> accounts = List.of(account("A", 3), account("B", 3), account("C", 3));
        for (long quantity = 1; quantity <= 9; quantity++) {
            AllocationPlan plan = allocator.allocate(notice("n" + quantity, "C1", quantity), accounts);
            BigInteger sum = ZERO;
            boolean withinShort = true;
            Map<String, BigInteger> shorts = new TreeMap<>();
            for (Account account : accounts) {
                shorts.put(account.accountId(), account.shortPosition());
            }
            for (AccountShare share : plan.shares()) {
                sum = sum.add(share.quantity());
                if (share.quantity().compareTo(shorts.get(share.accountId())) > 0) {
                    withinShort = false;
                }
            }
            check("exhaustive Q=" + quantity + " sum", sum.equals(BigInteger.valueOf(quantity)));
            check("exhaustive Q=" + quantity + " within short", withinShort);
        }
    }

    private static void testInputOrderDoesNotChangeResult() {
        Allocator allocator = new Allocator();
        ExerciseNotice notice = notice("n1", "C1", 10);
        List<Account> first = List.of(account("A", 5), account("B", 3), account("C", 8));
        List<Account> shuffled = List.of(account("C", 8), account("A", 5), account("B", 3));
        AllocationPlan plan1 = allocator.allocate(notice, first);
        AllocationPlan plan2 = allocator.allocate(notice, shuffled);
        check("shuffled shares identical", plan1.shares().equals(plan2.shares()),
                plan1.shares() + " vs " + plan2.shares());
        check("shuffled cursor trail identical",
                plan1.cursorTrail().equals(plan2.cursorTrail()));
        check("shuffled final cursor identical", plan1.finalCursor() == plan2.finalCursor());
    }

    private static void testZeroEligibleShortRejected() {
        Allocator allocator = new Allocator();
        expectReject("zero eligible short", RejectReason.ZERO_ELIGIBLE_SHORT,
                () -> allocator.allocate(notice("n1", "C1", 1),
                        List.of(account("A", 0), account("B", 0))));
    }

    private static void testAllShortRejectsZeroShortOnly() {
        Allocator allocator = new Allocator();
        expectReject("negative position invalid", RejectReason.INVALID_NOTICE,
                () -> allocator.allocate(notice("n1", "C1", 1),
                        List.of(new Account("A", BigInteger.valueOf(-1)))));
        expectReject("zero quantity invalid", RejectReason.INVALID_NOTICE,
                () -> allocator.allocate(notice("n1", "C1", 0), List.of(account("A", 5))));
    }

    private static ExerciseEngine engineWith(String contractId, Deliverable deliverable,
                                             boolean expired) {
        CheckpointStore store = new CheckpointStore();
        ExerciseEngine engine = new ExerciseEngine(store, new FailureInjector(), TODAY);
        Contract contract = new Contract(contractId, "FICT-" + contractId,
                expired ? PAST : FUTURE);
        engine.registerContract(contract, deliverable);
        return engine;
    }

    private static void testCashDeliveryTotals() {
        ExerciseEngine engine = engineWith("C1", cashOnly("C1"), false);
        BatchResult result = engine.processBatch(
                List.of(notice("k1", "C1", 4)),
                List.of(account("A", 3), account("B", 5)));
        BigInteger totalCash = ZERO;
        BigInteger totalQty = ZERO;
        for (Obligation obligation : result.obligations()) {
            totalQty = totalQty.add(obligation.assignedQuantity());
            for (Obligation.CashDelivery cash : obligation.cash()) {
                totalCash = totalCash.add(cash.unscaledAmount());
            }
        }
        check("cash: qty total", totalQty.equals(BigInteger.valueOf(4)));
        check("cash: amount total = qty * leg",
                totalCash.equals(BigInteger.valueOf(4L * 12345L)), totalCash.toString());
    }

    private static void testPhysicalDeliveryTotals() {
        ExerciseEngine engine = engineWith("C1", physicalOnly("C1"), false);
        BatchResult result = engine.processBatch(
                List.of(notice("k1", "C1", 7)),
                List.of(account("A", 2), account("B", 9)));
        BigInteger totalSecurities = ZERO;
        for (Obligation obligation : result.obligations()) {
            for (Obligation.SecurityDelivery security : obligation.securities()) {
                totalSecurities = totalSecurities.add(security.unscaledQuantity());
            }
        }
        check("physical: securities total", totalSecurities.equals(BigInteger.valueOf(7)),
                totalSecurities.toString());
    }

    private static void testCompoundDeliveryTotals() {
        ExerciseEngine engine = engineWith("C1", compound("C1"), false);
        BatchResult result = engine.processBatch(
                List.of(notice("k1", "C1", 6)),
                List.of(account("A", 1), account("B", 2), account("C", 3)));
        BigInteger securities = ZERO;
        BigInteger cash = ZERO;
        for (Obligation obligation : result.obligations()) {
            securities = securities.add(
                    obligation.securities().get(0).unscaledQuantity());
            cash = cash.add(obligation.cash().get(0).unscaledAmount());
        }
        check("compound: security leg total", securities.equals(BigInteger.valueOf(6)));
        check("compound: cash leg total", cash.equals(BigInteger.valueOf(6L * 12345L)),
                cash.toString());
    }

    private static void testTailLedgerTracked() {
        Deliverable deliverable = new Deliverable("C1",
                List.of(new Deliverable.SecurityLeg("FICT-STK", 3, BigInteger.valueOf(333), 0)),
                List.of());
        ExerciseEngine engine = engineWith("C1", deliverable, false);
        BatchResult result = engine.processBatch(
                List.of(notice("k1", "C1", 2)),
                List.of(account("A", 1), account("B", 5)));
        BigInteger deliveredTotal = ZERO;
        for (Obligation obligation : result.obligations()) {
            deliveredTotal = deliveredTotal.add(
                    obligation.securities().get(0).unscaledQuantity());
        }
        AllocationPlan plan = new Allocator().allocate(notice("k1", "C1", 2),
                List.of(account("A", 1), account("B", 5)));
        GenerationResult generated = new ObligationGenerator().generate(deliverable, plan);
        BigInteger exactTotal = ZERO;
        BigInteger tailTotal = ZERO;
        for (var entry : generated.tailLedger()) {
            exactTotal = exactTotal.add(entry.exactUnscaled());
            tailTotal = tailTotal.add(entry.remainderUnscaled());
            check("tail identity per account " + entry.accountId(),
                    entry.exactUnscaled().equals(
                            entry.deliveredUnscaled()
                                    .multiply(BigInteger.TEN.pow(entry.scale() - entry.settleScale()))
                                    .add(entry.remainderUnscaled())));
        }
        check("tail: exact = delivered*10^3 + tail",
                exactTotal.equals(deliveredTotal.multiply(BigInteger.TEN.pow(3)).add(tailTotal)),
                exactTotal + " vs " + deliveredTotal + " + " + tailTotal);
        check("tail: ledger non-empty", !generated.tailLedger().isEmpty());
    }

    private static void testDuplicateNoticeInSameBatchRejected() {
        ExerciseEngine engine = engineWith("C1", cashOnly("C1"), false);
        expectReject("duplicate within batch", RejectReason.DUPLICATE_NOTICE,
                () -> engine.processBatch(
                        List.of(notice("k1", "C1", 1), notice("k1", "C1", 1)),
                        List.of(account("A", 5))));
        check("after reject key not committed",
                !engine.checkpointStore().state().committedKeys().contains("k1"));
    }

    private static void testDuplicateNoticeAcrossBatchesRejected() {
        ExerciseEngine engine = engineWith("C1", cashOnly("C1"), false);
        List<Account> accounts = List.of(account("A", 5));
        engine.processBatch(List.of(notice("k1", "C1", 1)), accounts);
        expectReject("duplicate across batches", RejectReason.DUPLICATE_NOTICE,
                () -> engine.processBatch(List.of(notice("k1", "C1", 1)), accounts));
        check("state unchanged after replay attempt",
                engine.checkpointStore().state().obligations().size() == 1);
    }

    private static void testOverExerciseRejected() {
        ExerciseEngine engine = engineWith("C1", cashOnly("C1"), false);
        expectReject("over exercise single batch", RejectReason.OVER_EXERCISE,
                () -> engine.processBatch(
                        List.of(notice("k1", "C1", 10)),
                        List.of(account("A", 3), account("B", 6))));
    }

    private static void testCumulativeAcrossBatchesOverExercise() {
        ExerciseEngine engine = engineWith("C1", cashOnly("C1"), false);
        List<Account> accounts = List.of(account("A", 5), account("B", 5));
        engine.processBatch(List.of(notice("k1", "C1", 8)), accounts);
        expectReject("cumulative over exercise", RejectReason.OVER_EXERCISE,
                () -> engine.processBatch(List.of(notice("k2", "C1", 3)), accounts));
        check("after cumulative reject, k2 absent",
                !engine.checkpointStore().state().committedKeys().contains("k2"));
    }

    private static void testExpiredContractRejected() {
        ExerciseEngine engine = engineWith("C1", cashOnly("C1"), true);
        expectReject("expired contract", RejectReason.EXPIRED_CONTRACT,
                () -> engine.processBatch(List.of(notice("k1", "C1", 1)),
                        List.of(account("A", 5))));
    }

    private static void testUnknownContractRejected() {
        ExerciseEngine engine = engineWith("C1", cashOnly("C1"), false);
        expectReject("unknown contract", RejectReason.UNKNOWN_CONTRACT,
                () -> engine.processBatch(List.of(notice("k1", "C2", 1)),
                        List.of(account("A", 5))));
    }

    private static void testIllegalDeliverableRejected() {
        boolean invalidRatioCaught = false;
        try {
            new Deliverable.SecurityLeg("S", 2, BigInteger.valueOf(-1), 2);
        } catch (IllegalArgumentException expected) {
            invalidRatioCaught = true;
        }
        check("illegal negative ratio rejected", invalidRatioCaught);

        boolean invalidScaleCaught = false;
        try {
            new Deliverable.SecurityLeg("S", 2, BigInteger.ONE, 3);
        } catch (IllegalArgumentException expected) {
            invalidScaleCaught = true;
        }
        check("illegal settle scale rejected", invalidScaleCaught);

        boolean emptyDeliverableCaught = false;
        try {
            new Deliverable("C1", List.of(), List.of());
        } catch (IllegalArgumentException expected) {
            emptyDeliverableCaught = true;
        }
        check("empty deliverable rejected", emptyDeliverableCaught);
    }

    private static void testCursorContinuesAcrossNotices() {
        ExerciseEngine engine = engineWith("C1", physicalOnly("C1"), false);
        List<Account> accounts = List.of(account("A", 10), account("B", 10), account("C", 10));
        engine.processBatch(List.of(notice("k1", "C1", 1), notice("k2", "C1", 1)), accounts);
        Map<String, BigInteger> assigned =
                engine.checkpointStore().state().assignedByContract().get("C1");
        check("canonical remainder cursor: A=2",
                assigned.get("A").equals(BigInteger.valueOf(2))
                        && assigned.get("B").equals(ZERO)
                        && assigned.get("C").equals(ZERO),
                assigned.toString());
    }

    private static void testOracleMatchesEngine() {
        Deliverable[] deliverables = {cashOnly("C1"), physicalOnly("C1"),
                compound("C1"),
                new Deliverable("C1",
                        List.of(new Deliverable.SecurityLeg("FICT-STK", 3,
                                BigInteger.valueOf(333), 0)),
                        List.of(new Deliverable.CashLeg("CNX", 4,
                                BigInteger.valueOf(10001), 2)))};
        long[][] shorts = {{1, 1}, {7, 1, 3}, {3, 3, 3}};
        NaiveOracle oracle = new NaiveOracle();
        Allocator allocator = new Allocator();
        ObligationGenerator generator = new ObligationGenerator();
        int variants = 0;
        for (Deliverable deliverable : deliverables) {
            for (long[] shortPositions : shorts) {
                List<Account> accounts = new ArrayList<>();
                for (int i = 0; i < shortPositions.length; i++) {
                    accounts.add(account("ACC-" + (char) ('A' + i), shortPositions[i]));
                }
                BigInteger total = accounts.stream().map(Account::shortPosition)
                        .reduce(ZERO, BigInteger::add);
                for (long quantity = 1; quantity <= total.longValueExact(); quantity++) {
                    ExerciseNotice notice = notice("n", "C1", quantity);
                    int startCursor = variants % accounts.size();
                    AllocationPlan enginePlan = allocator.allocate(notice, accounts, startCursor);
                    AllocationPlan oraclePlan = oracle.allocate(notice, accounts, startCursor);
                    check("oracle shares q=" + quantity,
                            enginePlan.shares().equals(oraclePlan.shares()));
                    check("oracle cursor q=" + quantity,
                            enginePlan.cursorTrail().equals(oraclePlan.cursorTrail())
                                    && enginePlan.finalCursor() == oraclePlan.finalCursor());
                    GenerationResult engineResult = generator.generate(deliverable, enginePlan);
                    GenerationResult oracleResult = oracle.generate(deliverable, oraclePlan);
                    check("oracle obligations q=" + quantity,
                            engineResult.obligations().equals(oracleResult.obligations()));
                    check("oracle tail ledger q=" + quantity,
                            engineResult.tailLedger().equals(oracleResult.tailLedger()));
                    variants++;
                }
            }
        }
    }

    private static void assertStateUntouched(ExerciseEngine engine, String snapshotBefore) {
        byte[] snapshotAfter = engine.checkpointStore().exportSnapshot();
        check("external state byte-identical after failure",
                Arrays.equals(snapshotAfter, snapshotBefore.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static void testInjectedFailureAtAllocateRollsBack() {
        ExerciseEngine engine = engineWith("C1", cashOnly("C1"), false);
        String before = new String(engine.checkpointStore().exportSnapshot(),
                java.nio.charset.StandardCharsets.UTF_8);
        engine.failureInjector().arm(FailurePoint.ALLOCATE);
        expectReject("failure at allocate", RejectReason.INJECTED_FAILURE,
                () -> engine.processBatch(List.of(notice("k1", "C1", 2)),
                        List.of(account("A", 5))));
        assertStateUntouched(engine, before);
    }

    private static void testInjectedFailureAtGenerationRollsBack() {
        ExerciseEngine engine = engineWith("C1", compound("C1"), false);
        String before = new String(engine.checkpointStore().exportSnapshot(),
                java.nio.charset.StandardCharsets.UTF_8);
        engine.failureInjector().arm(FailurePoint.GENERATE_OBLIGATIONS);
        expectReject("failure at generate", RejectReason.INJECTED_FAILURE,
                () -> engine.processBatch(List.of(notice("k1", "C1", 2)),
                        List.of(account("A", 5))));
        assertStateUntouched(engine, before);
    }

    private static void testInjectedFailureAtWriteLogRollsBack() {
        ExerciseEngine engine = engineWith("C1", cashOnly("C1"), false);
        String before = new String(engine.checkpointStore().exportSnapshot(),
                java.nio.charset.StandardCharsets.UTF_8);
        engine.failureInjector().arm(FailurePoint.WRITE_LOG);
        expectReject("failure at write log", RejectReason.INJECTED_FAILURE,
                () -> engine.processBatch(List.of(notice("k1", "C1", 2)),
                        List.of(account("A", 5))));
        assertStateUntouched(engine, before);
    }

    private static void testFailureThenRetrySucceeds() {
        ExerciseEngine engine = engineWith("C1", cashOnly("C1"), false);
        engine.failureInjector().arm(FailurePoint.WRITE_LOG);
        expectReject("armed failure fires", RejectReason.INJECTED_FAILURE,
                () -> engine.processBatch(List.of(notice("k1", "C1", 2)),
                        List.of(account("A", 5))));
        BatchResult result = engine.processBatch(List.of(notice("k1", "C1", 2)),
                List.of(account("A", 5)));
        check("retry after failure commits", result.obligations().size() == 1);
    }

    private static void testReplayIsByteIdentical() {
        List<Account> accounts = List.of(account("A", 7), account("B", 4), account("C", 2));
        ExerciseEngine first = engineWith("C1", compound("C1"), false);
        first.processBatch(List.of(notice("z-key", "C1", 3), notice("a-key", "C1", 2)),
                accounts);
        first.processBatch(List.of(notice("m-key", "C1", 1)), accounts);
        byte[] firstSnapshot = first.checkpointStore().exportSnapshot();

        ExerciseEngine replayed = engineWith("C1", compound("C1"), false);
        replayed.processBatch(List.of(notice("m-key", "C1", 1)),
                List.of(account("C", 2), account("A", 7), account("B", 4)));
        replayed.processBatch(List.of(notice("a-key", "C1", 2), notice("z-key", "C1", 3)),
                accounts);
        byte[] replayedSnapshot = replayed.checkpointStore().exportSnapshot();
        check("replay snapshot byte-identical",
                Arrays.equals(firstSnapshot, replayedSnapshot));
    }

    private static void testSnapshotRestoreIsByteIdentical() {
        ExerciseEngine engine = engineWith("C1", compound("C1"), false);
        List<Account> accounts = List.of(account("A", 7), account("B", 4));
        engine.processBatch(List.of(notice("k1", "C1", 3)), accounts);
        byte[] snapshot = engine.checkpointStore().exportSnapshot();

        CheckpointStore restoredStore = new CheckpointStore();
        restoredStore.importSnapshot(snapshot);
        ExerciseEngine restored = new ExerciseEngine(restoredStore, new FailureInjector(), TODAY);
        restored.processBatch(List.of(notice("k2", "C1", 2)),
                List.of(account("B", 4), account("A", 7)));

        ExerciseEngine continued = engineWith("C1", compound("C1"), false);
        continued.checkpointStore().importSnapshot(snapshot);
        continued.processBatch(List.of(notice("k2", "C1", 2)), accounts);
        check("checkpoint restore converges byte-identical",
                Arrays.equals(restored.checkpointStore().exportSnapshot(),
                        continued.checkpointStore().exportSnapshot()));
    }
}
