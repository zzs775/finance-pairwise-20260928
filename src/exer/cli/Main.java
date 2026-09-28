package exer.cli;

import exer.CheckpointStore;
import exer.FailureInjector;
import exer.FailurePoint;
import exer.ExerciseEngine;
import exer.model.Account;
import exer.model.BatchResult;
import exer.model.Contract;
import exer.model.Deliverable;
import exer.model.EngineException;
import exer.model.ExerciseNotice;
import exer.model.Obligation;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 命令行入口（仅本地虚构数据）。
 * demo     运行内置演示：比例余数、复合交割、重复拒绝、故障回滚与检查点恢复
 * selftest 运行自动化测试
 */
public final class Main {

    private static final long TODAY = 20000L;

    public static void main(String[] args) {
        String command = args.length == 0 ? "demo" : args[0];
        switch (command) {
            case "selftest" -> exer.Tests.main(new String[0]);
            case "demo" -> runDemo();
            default -> {
                System.out.println("unknown command: " + command);
                System.out.println("usage: exer-cli [demo|selftest]");
                System.exit(2);
            }
        }
    }

    private static void runDemo() {
        System.out.println("== option exercise assignment engine demo ==");
        CheckpointStore store = new CheckpointStore();
        FailureInjector injector = new FailureInjector();
        ExerciseEngine engine = new ExerciseEngine(store, injector, TODAY);

        Deliverable deliverable = new Deliverable("OPT-FICT-001",
                List.of(new Deliverable.SecurityLeg("FICT-STK", 0, BigInteger.ONE, 0)),
                List.of(new Deliverable.CashLeg("CNX", 2, BigInteger.valueOf(12345), 2)));
        engine.registerContract(
                new Contract("OPT-FICT-001", "FICT-UNDERLYING", TODAY + 14L), deliverable);

        List<Account> accounts = List.of(
                new Account("ACC-ALPHA", BigInteger.valueOf(7)),
                new Account("ACC-BETA", BigInteger.valueOf(4)),
                new Account("ACC-GAMMA", BigInteger.valueOf(2)));

        BatchResult first = engine.processBatch(List.of(
                new ExerciseNotice("notice-001", "OPT-FICT-001", BigInteger.valueOf(4))),
                accounts);
        printBatch(first);

        System.out.println("-- replay duplicate notice (must be classified DUPLICATE_NOTICE) --");
        try {
            engine.processBatch(List.of(
                    new ExerciseNotice("notice-001", "OPT-FICT-001", BigInteger.valueOf(4))),
                    accounts);
        } catch (EngineException exception) {
            System.out.println("rejected: " + exception.reason() + " " + exception.getMessage());
        }

        System.out.println("-- injected failure at WRITE_LOG (state must stay unchanged) --");
        byte[] before = store.exportSnapshot();
        injector.arm(FailurePoint.WRITE_LOG);
        try {
            engine.processBatch(List.of(
                    new ExerciseNotice("notice-002", "OPT-FICT-001", BigInteger.valueOf(2))),
                    accounts);
        } catch (EngineException exception) {
            System.out.println("injected: " + exception.reason());
        }
        byte[] after = store.exportSnapshot();
        System.out.println("state unchanged after failure: "
                + java.util.Arrays.equals(before, after));

        System.out.println("-- retry same notice after failure, then restore snapshot --");
        BatchResult retry = engine.processBatch(List.of(
                new ExerciseNotice("notice-002", "OPT-FICT-001", BigInteger.valueOf(2))),
                accounts);
        printBatch(retry);
        byte[] checkpoint = store.exportSnapshot();

        CheckpointStore restoredStore = new CheckpointStore();
        restoredStore.importSnapshot(checkpoint);
        System.out.println("checkpoint restored byte-identical: "
                + java.util.Arrays.equals(checkpoint, restoredStore.exportSnapshot()));
    }

    private static void printBatch(BatchResult result) {
        System.out.println("-- " + result.batchId() + " committed --");
        for (Obligation obligation : result.obligations()) {
            StringBuilder builder = new StringBuilder();
            builder.append(obligation.noticeKey()).append(" -> ")
                    .append(obligation.accountId()).append(" qty=")
                    .append(obligation.assignedQuantity());
            for (Obligation.SecurityDelivery security : obligation.securities()) {
                builder.append(" | SEC ").append(security.symbol()).append(' ')
                        .append(security.unscaledQuantity())
                        .append("*10^-").append(security.scale());
            }
            for (Obligation.CashDelivery cash : obligation.cash()) {
                builder.append(" | CASH ").append(cash.currency()).append(' ')
                        .append(cash.unscaledAmount())
                        .append("*10^-").append(cash.scale());
            }
            System.out.println(builder);
        }
    }

    private Main() {
    }
}
