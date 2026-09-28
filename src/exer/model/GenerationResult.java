package exer.model;

import java.util.List;

/** 义务生成结果：义务列表 + 可追踪的尾差台账。 */
public record GenerationResult(List<Obligation> obligations, List<TailLedgerEntry> tailLedger) {

    public GenerationResult {
        obligations = List.copyOf(obligations);
        tailLedger = List.copyOf(tailLedger);
    }
}
