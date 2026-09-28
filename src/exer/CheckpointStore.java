package exer;

import exer.model.Contract;
import exer.model.Deliverable;
import exer.model.Obligation;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** 检查点与日志存储：支持快照导出/导入与字节级一致重放。 */
public final class CheckpointStore {

    /** 引擎全部可持久化状态，始终按确定性顺序（字典序）组织。 */
    public record State(Map<String, Contract> contracts,
                        Map<String, Deliverable> deliverables,
                        Map<String, Map<String, BigInteger>> assignedByContract,
                        Map<String, Integer> cursorByContract,
                        Set<String> committedKeys,
                        List<Obligation> obligations,
                        long batchSeq) {

        public State {
            contracts = new TreeMap<>(contracts);
            deliverables = new TreeMap<>(deliverables);
            TreeMap<String, Map<String, BigInteger>> assigned = new TreeMap<>();
            for (Map.Entry<String, Map<String, BigInteger>> entry : assignedByContract.entrySet()) {
                assigned.put(entry.getKey(), new TreeMap<>(entry.getValue()));
            }
            assignedByContract = assigned;
            cursorByContract = new TreeMap<>(cursorByContract);
            committedKeys = new TreeSet<>(committedKeys);
            List<Obligation> sortedObligations = new java.util.ArrayList<>(obligations);
            sortedObligations.sort(java.util.Comparator
                    .comparing(Obligation::noticeKey)
                    .thenComparing(Obligation::accountId));
            obligations = List.copyOf(sortedObligations);
        }

        public static State empty() {
            return new State(Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), List.of(), 0L);
        }
    }

    private State state;

    public CheckpointStore() {
        this.state = State.empty();
    }

    public synchronized State state() {
        return state;
    }

    /** 用暂存状态一次性替换当前状态（提交点）。 */
    public synchronized void replaceState(State next) {
        this.state = next;
    }

    public synchronized byte[] exportSnapshot() {
        return encode(state);
    }

    public synchronized void importSnapshot(byte[] data) {
        this.state = decode(data);
    }

    static byte[] encode(State state) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLine(out, "EXER-CHECKPOINT-V1");
        writeLine(out, Long.toString(state.batchSeq()));
        writeLine(out, Integer.toString(state.contracts().size()));
        for (Contract contract : state.contracts().values()) {
            writeLine(out, contract.contractId() + "|" + contract.underlyingSymbol()
                    + "|" + contract.expiryEpochDay());
        }
        writeLine(out, Integer.toString(state.deliverables().size()));
        for (Deliverable deliverable : state.deliverables().values()) {
            writeLine(out, deliverable.contractId()
                    + "|S|" + deliverable.securityLegs().size()
                    + "|C|" + deliverable.cashLegs().size());
            for (Deliverable.SecurityLeg leg : deliverable.securityLegs()) {
                writeLine(out, leg.symbol() + "|" + leg.scale() + "|"
                        + leg.unscaledPerContract() + "|" + leg.settleScale());
            }
            for (Deliverable.CashLeg leg : deliverable.cashLegs()) {
                writeLine(out, leg.currency() + "|" + leg.scale() + "|"
                        + leg.unscaledPerContract() + "|" + leg.settleScale());
            }
        }
        writeLine(out, Integer.toString(state.assignedByContract().size()));
        for (Map.Entry<String, Map<String, BigInteger>> entry : state.assignedByContract().entrySet()) {
            writeLine(out, entry.getKey() + "|" + entry.getValue().size());
            for (Map.Entry<String, BigInteger> acc : entry.getValue().entrySet()) {
                writeLine(out, acc.getKey() + "|" + acc.getValue());
            }
        }
        writeLine(out, Integer.toString(state.cursorByContract().size()));
        for (Map.Entry<String, Integer> entry : state.cursorByContract().entrySet()) {
            writeLine(out, entry.getKey() + "|" + entry.getValue());
        }
        writeLine(out, Integer.toString(state.committedKeys().size()));
        for (String key : state.committedKeys()) {
            writeLine(out, key);
        }
        writeLine(out, Integer.toString(state.obligations().size()));
        for (Obligation obligation : state.obligations()) {
            writeLine(out, obligation.noticeKey() + "|" + obligation.accountId() + "|" + obligation.contractId()
                    + "|" + obligation.assignedQuantity()
                    + "|S|" + obligation.securities().size()
                    + "|C|" + obligation.cash().size());
            for (Obligation.SecurityDelivery d : obligation.securities()) {
                writeLine(out, d.symbol() + "|" + d.scale() + "|" + d.unscaledQuantity());
            }
            for (Obligation.CashDelivery d : obligation.cash()) {
                writeLine(out, d.currency() + "|" + d.scale() + "|" + d.unscaledAmount());
            }
        }
        return out.toByteArray();
    }

    static State decode(byte[] data) {
        List<String> lines = new String(data, StandardCharsets.UTF_8).lines().toList();
        Cursor cursor = new Cursor(lines);
        expect(cursor, "EXER-CHECKPOINT-V1");
        long batchSeq = Long.parseLong(cursor.next());
        int contractCount = Integer.parseInt(cursor.next());
        Map<String, Contract> contracts = new TreeMap<>();
        for (int i = 0; i < contractCount; i++) {
            String[] parts = cursor.next().split("\\|", -1);
            Contract contract = new Contract(parts[0], parts[1], Long.parseLong(parts[2]));
            contracts.put(contract.contractId(), contract);
        }
        int deliverableCount = Integer.parseInt(cursor.next());
        Map<String, Deliverable> deliverables = new TreeMap<>();
        for (int i = 0; i < deliverableCount; i++) {
            String[] header = cursor.next().split("\\|", -1);
            String contractId = header[0];
            int securityCount = Integer.parseInt(header[2]);
            int cashCount = Integer.parseInt(header[4]);
            List<Deliverable.SecurityLeg> securityLegs = new ArrayList<>();
            for (int j = 0; j < securityCount; j++) {
                String[] parts = cursor.next().split("\\|", -1);
                securityLegs.add(new Deliverable.SecurityLeg(parts[0], Integer.parseInt(parts[1]),
                        new BigInteger(parts[2]), Integer.parseInt(parts[3])));
            }
            List<Deliverable.CashLeg> cashLegs = new ArrayList<>();
            for (int j = 0; j < cashCount; j++) {
                String[] parts = cursor.next().split("\\|", -1);
                cashLegs.add(new Deliverable.CashLeg(parts[0], Integer.parseInt(parts[1]),
                        new BigInteger(parts[2]), Integer.parseInt(parts[3])));
            }
            deliverables.put(contractId, new Deliverable(contractId, securityLegs, cashLegs));
        }
        int assignedContracts = Integer.parseInt(cursor.next());
        Map<String, Map<String, BigInteger>> assigned = new TreeMap<>();
        for (int i = 0; i < assignedContracts; i++) {
            String[] header = cursor.next().split("\\|", -1);
            String contractId = header[0];
            int accountCount = Integer.parseInt(header[1]);
            Map<String, BigInteger> perAccount = new TreeMap<>();
            for (int j = 0; j < accountCount; j++) {
                String[] parts = cursor.next().split("\\|", -1);
                perAccount.put(parts[0], new BigInteger(parts[1]));
            }
            assigned.put(contractId, perAccount);
        }
        int cursorCount = Integer.parseInt(cursor.next());
        Map<String, Integer> cursors = new TreeMap<>();
        for (int i = 0; i < cursorCount; i++) {
            String[] parts = cursor.next().split("\\|", -1);
            cursors.put(parts[0], Integer.valueOf(parts[1]));
        }
        int keyCount = Integer.parseInt(cursor.next());
        Set<String> keys = new TreeSet<>();
        for (int i = 0; i < keyCount; i++) {
            keys.add(cursor.next());
        }
        int obligationCount = Integer.parseInt(cursor.next());
        List<Obligation> obligations = new ArrayList<>();
        for (int i = 0; i < obligationCount; i++) {
            String[] header = cursor.next().split("\\|", -1);
            String noticeKey = header[0];
            String accountId = header[1];
            String contractId = header[2];
            BigInteger quantity = new BigInteger(header[3]);
            int securityCount = Integer.parseInt(header[5]);
            int cashCount = Integer.parseInt(header[7]);
            List<Obligation.SecurityDelivery> securities = new ArrayList<>();
            for (int j = 0; j < securityCount; j++) {
                String[] parts = cursor.next().split("\\|", -1);
                securities.add(new Obligation.SecurityDelivery(parts[0],
                        Integer.parseInt(parts[1]), new BigInteger(parts[2])));
            }
            List<Obligation.CashDelivery> cash = new ArrayList<>();
            for (int j = 0; j < cashCount; j++) {
                String[] parts = cursor.next().split("\\|", -1);
                cash.add(new Obligation.CashDelivery(parts[0],
                        Integer.parseInt(parts[1]), new BigInteger(parts[2])));
            }
            obligations.add(new Obligation(noticeKey, accountId, contractId, quantity, securities, cash));
        }
        return new State(contracts, deliverables, assigned, cursors, keys, obligations, batchSeq);
    }

    private static void writeLine(ByteArrayOutputStream out, String line) {
        out.writeBytes(line.getBytes(StandardCharsets.UTF_8));
        out.write('\n');
    }

    private static void expect(Cursor cursor, String marker) {
        String actual = cursor.next();
        if (!marker.equals(actual)) {
            throw new IllegalArgumentException("bad checkpoint marker: " + actual);
        }
    }

    private static final class Cursor {
        private final List<String> lines;
        private int index;

        private Cursor(List<String> lines) {
            this.lines = lines;
        }

        private String next() {
            if (index >= lines.size()) {
                throw new IllegalArgumentException("truncated checkpoint");
            }
            return lines.get(index++);
        }
    }
}
